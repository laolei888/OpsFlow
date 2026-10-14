package com.opsflow.slo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.alert.MonitoringAlert;
import com.opsflow.alert.MonitoringAlertMapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.statuspage.StatusPageComponent;
import com.opsflow.statuspage.StatusPageComponentMapper;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SLO samples are explicit evidence. Monitoring alerts may be attached as evidence,
 * but an alert alone never guesses a request denominator or changes a public status page.
 */
@Service
@RequiredArgsConstructor
public class SloService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final SloDefinitionMapper definitionMapper;
    private final SloMeasurementMapper measurementMapper;
    private final SloBreachEventMapper breachMapper;
    private final StatusPageComponentMapper statusComponentMapper;
    private final MonitoringAlertMapper monitoringAlertMapper;
    private final TicketService ticketService;
    private final IncidentMapper incidentMapper;
    private final AuditLogService auditLogService;

    public ReliabilityCenterView center(Long tenantId) {
        List<SloDefinition> definitions = definitions(tenantId);
        List<DefinitionView> views = definitions.stream()
                .map(definition -> new DefinitionView(definition, componentName(definition.getStatusComponentId(), tenantId),
                        snapshot(definition, tenantId))).toList();
        Map<Long, String> definitionNames = definitions.stream()
                .collect(Collectors.toMap(SloDefinition::getId, SloDefinition::getName));
        List<BreachView> breaches = breachMapper.selectList(new LambdaQueryWrapper<SloBreachEvent>()
                        .eq(SloBreachEvent::getTenantId, tenantId)
                        .orderByAsc(SloBreachEvent::getStatus)
                        .orderByDesc(SloBreachEvent::getUpdatedAt)
                        .last("LIMIT 120"))
                .stream().map(breach -> new BreachView(breach, definitionNames.getOrDefault(breach.getDefinitionId(),
                        "已删除的 SLO"))).toList();
        List<StatusPageComponent> components = statusComponentMapper.selectList(new LambdaQueryWrapper<StatusPageComponent>()
                .eq(StatusPageComponent::getTenantId, tenantId)
                .orderByAsc(StatusPageComponent::getDisplayOrder)
                .orderByAsc(StatusPageComponent::getName));
        return new ReliabilityCenterView(views, breaches, components);
    }

    public DefinitionDetail detail(Long definitionId, Long tenantId) {
        SloDefinition definition = getDefinition(definitionId, tenantId);
        List<SloMeasurement> measurements = measurementMapper.selectList(new LambdaQueryWrapper<SloMeasurement>()
                .eq(SloMeasurement::getTenantId, tenantId)
                .eq(SloMeasurement::getDefinitionId, definitionId)
                .orderByDesc(SloMeasurement::getOccurredAt)
                .orderByDesc(SloMeasurement::getId)
                .last("LIMIT 120"));
        List<SloBreachEvent> breaches = breachMapper.selectList(new LambdaQueryWrapper<SloBreachEvent>()
                .eq(SloBreachEvent::getTenantId, tenantId)
                .eq(SloBreachEvent::getDefinitionId, definitionId)
                .orderByDesc(SloBreachEvent::getUpdatedAt)
                .last("LIMIT 80"));
        return new DefinitionDetail(definition, componentName(definition.getStatusComponentId(), tenantId),
                snapshot(definition, tenantId), measurements, breaches);
    }

    public List<SloDefinition> definitions(Long tenantId) {
        return definitionMapper.selectList(new LambdaQueryWrapper<SloDefinition>()
                .eq(SloDefinition::getTenantId, tenantId)
                .orderByDesc(SloDefinition::getEnabled)
                .orderByAsc(SloDefinition::getName));
    }

    @Transactional
    public SloDefinition createDefinition(DefinitionCommand command, Long tenantId, Long actorId) {
        SloDefinition definition = new SloDefinition();
        applyDefinition(definition, command, tenantId);
        LocalDateTime now = LocalDateTime.now();
        definition.setCreatedBy(actorId);
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);
        try {
            definitionMapper.insert(definition);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("当前租户已存在同名 SLO，请使用其他名称");
        }
        auditLogService.record(tenantId, actorId, "SLO_DEFINITION", definition.getId(), "SLO_DEFINITION_CREATED",
                null, definition, "创建服务等级目标");
        return definition;
    }

    @Transactional
    public SloDefinition updateDefinition(Long id, DefinitionCommand command, Long tenantId, Long actorId) {
        SloDefinition definition = getDefinition(id, tenantId);
        SloDefinition before = copyDefinition(definition);
        applyDefinition(definition, command, tenantId);
        definition.setUpdatedAt(LocalDateTime.now());
        try {
            if (definitionMapper.updateById(definition) == 0) throw new BusinessException("SLO 更新失败，请刷新后重试");
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("当前租户已存在同名 SLO，请使用其他名称");
        }
        auditLogService.record(tenantId, actorId, "SLO_DEFINITION", id, "SLO_DEFINITION_UPDATED",
                before, definition, "更新服务等级目标");
        return definition;
    }

    @Transactional
    public MeasurementResult recordMeasurement(Long definitionId, MeasurementCommand command, Long tenantId, Long actorId) {
        SloDefinition definition = getDefinition(definitionId, tenantId);
        if (!Boolean.TRUE.equals(definition.getEnabled())) throw new BusinessException("该 SLO 已停用，不能记录样本");
        long total = command.totalRequests() == null ? 0 : command.totalRequests();
        long good = command.goodRequests() == null ? -1 : command.goodRequests();
        if (total <= 0) throw new BusinessException("总请求数必须大于 0");
        if (good < 0 || good > total) throw new BusinessException("成功请求数必须在 0 到总请求数之间");
        if (command.sourceAlertId() != null) verifyAlert(command.sourceAlertId(), tenantId);
        LocalDateTime now = LocalDateTime.now();
        SloMeasurement measurement = new SloMeasurement();
        measurement.setTenantId(tenantId);
        measurement.setDefinitionId(definitionId);
        measurement.setSource(command.sourceAlertId() == null ? SloMeasurementSource.MANUAL : SloMeasurementSource.ALERT_EVIDENCE);
        measurement.setSourceAlertId(command.sourceAlertId());
        measurement.setTotalRequests(total);
        measurement.setGoodRequests(good);
        measurement.setAvailabilityPercent(percent(good, total));
        measurement.setNote(optional(command.note(), 1000, "样本说明"));
        measurement.setOccurredAt(command.occurredAt() == null ? now : command.occurredAt());
        if (measurement.getOccurredAt().isAfter(now.plusMinutes(5))) throw new BusinessException("样本时间不能晚于当前时间");
        measurement.setRecordedBy(actorId);
        measurement.setCreatedAt(now);
        measurementMapper.insert(measurement);
        ReliabilitySnapshot snapshot = snapshot(definition, tenantId);
        SloBreachEvent breach = maintainBreach(definition, snapshot, measurement, tenantId, actorId, now);
        auditLogService.record(tenantId, actorId, "SLO_DEFINITION", definitionId, "SLO_MEASUREMENT_RECORDED",
                null, measurementAuditView(measurement, snapshot), "记录 SLI 样本并重新计算错误预算");
        return new MeasurementResult(measurement, snapshot, breach);
    }

    @Transactional
    public SloBreachEvent acknowledge(Long breachId, Long tenantId, Long actorId) {
        SloBreachEvent breach = getBreach(breachId, tenantId);
        if (breach.getStatus() == SloBreachStatus.RESOLVED) throw new BusinessException("已解决的预算违规不能再确认");
        if (breach.getStatus() == SloBreachStatus.ACKNOWLEDGED) return breach;
        breach.setStatus(SloBreachStatus.ACKNOWLEDGED);
        breach.setAcknowledgedBy(actorId);
        breach.setAcknowledgedAt(LocalDateTime.now());
        breach.setUpdatedAt(LocalDateTime.now());
        breachMapper.updateById(breach);
        auditLogService.record(tenantId, actorId, "SLO_BREACH", breachId, "SLO_BREACH_ACKNOWLEDGED",
                SloBreachStatus.OPEN, SloBreachStatus.ACKNOWLEDGED, "确认错误预算违规");
        return breach;
    }

    @Transactional
    public SloBreachEvent resolve(Long breachId, Long tenantId, Long actorId) {
        SloBreachEvent breach = getBreach(breachId, tenantId);
        if (breach.getStatus() == SloBreachStatus.RESOLVED) return breach;
        SloBreachStatus before = breach.getStatus();
        LocalDateTime now = LocalDateTime.now();
        breach.setStatus(SloBreachStatus.RESOLVED);
        breach.setResolvedBy(actorId);
        breach.setResolvedAt(now);
        breach.setUpdatedAt(now);
        breachMapper.updateById(breach);
        auditLogService.record(tenantId, actorId, "SLO_BREACH", breachId, "SLO_BREACH_RESOLVED",
                before, SloBreachStatus.RESOLVED, "关闭错误预算违规");
        return breach;
    }

    @Transactional
    public SloBreachEvent linkTicket(Long breachId, Long ticketId, Long tenantId, Long actorId) {
        SloBreachEvent breach = getBreach(breachId, tenantId);
        ticketService.get(ticketId, tenantId);
        Long before = breach.getLinkedTicketId();
        breach.setLinkedTicketId(ticketId);
        breach.setUpdatedAt(LocalDateTime.now());
        breachMapper.updateById(breach);
        auditLogService.record(tenantId, actorId, "SLO_BREACH", breachId, "SLO_BREACH_TICKET_LINKED",
                before, ticketId, "关联错误预算违规处置工单");
        return breach;
    }

    @Transactional
    public SloBreachEvent linkIncident(Long breachId, Long incidentId, Long tenantId, Long actorId) {
        SloBreachEvent breach = getBreach(breachId, tenantId);
        Incident incident = incidentMapper.selectOne(new LambdaQueryWrapper<Incident>()
                .eq(Incident::getId, incidentId).eq(Incident::getTenantId, tenantId));
        if (incident == null) throw new BusinessException("关联的重大事件不存在");
        Long before = breach.getLinkedIncidentId();
        breach.setLinkedIncidentId(incidentId);
        breach.setUpdatedAt(LocalDateTime.now());
        breachMapper.updateById(breach);
        auditLogService.record(tenantId, actorId, "SLO_BREACH", breachId, "SLO_BREACH_INCIDENT_LINKED",
                before, incidentId, "关联错误预算违规重大事件");
        return breach;
    }

    private void applyDefinition(SloDefinition definition, DefinitionCommand command, Long tenantId) {
        BigDecimal target = scale(command.targetPercent(), "目标可用性");
        BigDecimal warning = scale(command.warningBudgetPercent(), "预警预算阈值");
        BigDecimal critical = scale(command.criticalBudgetPercent(), "严重预算阈值");
        if (target.compareTo(new BigDecimal("90")) < 0 || target.compareTo(HUNDRED) > 0) {
            throw new BusinessException("目标可用性必须在 90% 到 100% 之间");
        }
        if (warning.compareTo(BigDecimal.ZERO) < 0 || warning.compareTo(HUNDRED) > 0
                || critical.compareTo(BigDecimal.ZERO) < 0 || critical.compareTo(warning) > 0) {
            throw new BusinessException("预算阈值必须满足 0 ≤ 严重阈值 ≤ 预警阈值 ≤ 100");
        }
        int windowDays = command.windowDays() == null ? 30 : command.windowDays();
        if (windowDays < 1 || windowDays > 90) throw new BusinessException("统计窗口必须在 1 到 90 天之间");
        if (command.statusComponentId() != null) verifyComponent(command.statusComponentId(), tenantId);
        definition.setTenantId(tenantId);
        definition.setName(required(command.name(), "SLO 名称", 120));
        definition.setDescription(optional(command.description(), 1000, "SLO 说明"));
        definition.setStatusComponentId(command.statusComponentId());
        definition.setTargetPercent(target);
        definition.setWindowDays(windowDays);
        definition.setWarningBudgetPercent(warning);
        definition.setCriticalBudgetPercent(critical);
        definition.setEnabled(command.enabled() == null || command.enabled());
    }

    private ReliabilitySnapshot snapshot(SloDefinition definition, Long tenantId) {
        LocalDateTime windowStart = LocalDateTime.now().minusDays(definition.getWindowDays());
        List<SloMeasurement> measurements = measurementMapper.selectList(new LambdaQueryWrapper<SloMeasurement>()
                .eq(SloMeasurement::getTenantId, tenantId)
                .eq(SloMeasurement::getDefinitionId, definition.getId())
                .ge(SloMeasurement::getOccurredAt, windowStart));
        long total = measurements.stream().mapToLong(SloMeasurement::getTotalRequests).sum();
        long good = measurements.stream().mapToLong(SloMeasurement::getGoodRequests).sum();
        if (total == 0) return new ReliabilitySnapshot(definition.getId(), definition.getWindowDays(), windowStart,
                false, 0, 0, 0, null, definition.getTargetPercent(), null, null, null);
        long bad = total - good;
        BigDecimal availability = percent(good, total);
        BigDecimal allowedBad = BigDecimal.valueOf(total).multiply(HUNDRED.subtract(definition.getTargetPercent()))
                .divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal remaining = allowedBad.compareTo(BigDecimal.ZERO) == 0
                ? (bad == 0 ? HUNDRED : BigDecimal.ZERO)
                : allowedBad.subtract(BigDecimal.valueOf(bad)).multiply(HUNDRED).divide(allowedBad, 3, RoundingMode.HALF_UP);
        remaining = remaining.max(BigDecimal.ZERO).min(HUNDRED).setScale(3, RoundingMode.HALF_UP);
        BigDecimal consumed = HUNDRED.subtract(remaining).setScale(3, RoundingMode.HALF_UP);
        return new ReliabilitySnapshot(definition.getId(), definition.getWindowDays(), windowStart, true,
                measurements.size(), total, bad, availability, definition.getTargetPercent(), consumed, remaining,
                breachLevel(definition, remaining));
    }

    private SloBreachEvent maintainBreach(SloDefinition definition, ReliabilitySnapshot snapshot,
                                          SloMeasurement measurement, Long tenantId, Long actorId, LocalDateTime now) {
        SloBreachLevel level = snapshot.currentLevel();
        if (level == null) return null;
        SloBreachEvent existing = breachMapper.selectOne(new LambdaQueryWrapper<SloBreachEvent>()
                .eq(SloBreachEvent::getTenantId, tenantId)
                .eq(SloBreachEvent::getDefinitionId, definition.getId())
                .in(SloBreachEvent::getStatus, List.of(SloBreachStatus.OPEN, SloBreachStatus.ACKNOWLEDGED))
                .orderByDesc(SloBreachEvent::getUpdatedAt)
                .last("LIMIT 1"));
        if (existing == null) {
            SloBreachEvent created = new SloBreachEvent();
            created.setTenantId(tenantId);
            created.setBreachNo("SLO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT));
            created.setDefinitionId(definition.getId());
            created.setSourceMeasurementId(measurement.getId());
            created.setLevel(level);
            created.setStatus(SloBreachStatus.OPEN);
            created.setAvailabilityPercent(snapshot.availabilityPercent());
            created.setBudgetRemainingPercent(snapshot.budgetRemainingPercent());
            created.setTitle(breachTitle(definition, level));
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            breachMapper.insert(created);
            auditLogService.record(tenantId, actorId, "SLO_BREACH", created.getId(), "SLO_BREACH_CREATED",
                    null, created, "错误预算达到 " + level + " 阈值");
            return created;
        }
        SloBreachLevel beforeLevel = existing.getLevel();
        existing.setSourceMeasurementId(measurement.getId());
        existing.setAvailabilityPercent(snapshot.availabilityPercent());
        existing.setBudgetRemainingPercent(snapshot.budgetRemainingPercent());
        existing.setUpdatedAt(now);
        if (levelRank(level) > levelRank(existing.getLevel())) {
            existing.setLevel(level);
            existing.setTitle(breachTitle(definition, level));
            breachMapper.updateById(existing);
            auditLogService.record(tenantId, actorId, "SLO_BREACH", existing.getId(), "SLO_BREACH_ESCALATED",
                    beforeLevel, level, "错误预算违规升级");
        } else {
            breachMapper.updateById(existing);
        }
        return existing;
    }

    private SloBreachLevel breachLevel(SloDefinition definition, BigDecimal remaining) {
        if (remaining == null) return null;
        if (remaining.compareTo(definition.getCriticalBudgetPercent()) <= 0) return SloBreachLevel.CRITICAL;
        if (remaining.compareTo(definition.getWarningBudgetPercent()) <= 0) return SloBreachLevel.WARNING;
        return null;
    }

    private void verifyComponent(Long componentId, Long tenantId) {
        StatusPageComponent component = statusComponentMapper.selectOne(new LambdaQueryWrapper<StatusPageComponent>()
                .eq(StatusPageComponent::getId, componentId).eq(StatusPageComponent::getTenantId, tenantId));
        if (component == null) throw new BusinessException("关联的服务状态组件不存在");
    }

    private void verifyAlert(Long alertId, Long tenantId) {
        MonitoringAlert alert = monitoringAlertMapper.selectOne(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getId, alertId).eq(MonitoringAlert::getTenantId, tenantId));
        if (alert == null) throw new BusinessException("关联的监控告警不存在");
    }

    private SloDefinition getDefinition(Long id, Long tenantId) {
        SloDefinition definition = definitionMapper.selectOne(new LambdaQueryWrapper<SloDefinition>()
                .eq(SloDefinition::getId, id).eq(SloDefinition::getTenantId, tenantId));
        if (definition == null) throw new BusinessException("SLO 不存在");
        return definition;
    }

    private SloBreachEvent getBreach(Long id, Long tenantId) {
        SloBreachEvent breach = breachMapper.selectOne(new LambdaQueryWrapper<SloBreachEvent>()
                .eq(SloBreachEvent::getId, id).eq(SloBreachEvent::getTenantId, tenantId));
        if (breach == null) throw new BusinessException("错误预算违规不存在");
        return breach;
    }

    private String componentName(Long componentId, Long tenantId) {
        if (componentId == null) return null;
        StatusPageComponent component = statusComponentMapper.selectOne(new LambdaQueryWrapper<StatusPageComponent>()
                .eq(StatusPageComponent::getId, componentId).eq(StatusPageComponent::getTenantId, tenantId));
        return component == null ? "已删除的服务组件" : component.getName();
    }

    private BigDecimal percent(long part, long total) {
        return BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
    }

    private int levelRank(SloBreachLevel level) {
        return level == SloBreachLevel.CRITICAL ? 2 : 1;
    }

    private String breachTitle(SloDefinition definition, SloBreachLevel level) {
        return definition.getName() + (level == SloBreachLevel.CRITICAL ? " 错误预算已耗尽" : " 错误预算预警");
    }

    private Map<String, Object> measurementAuditView(SloMeasurement measurement, ReliabilitySnapshot snapshot) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("measurementId", measurement.getId());
        view.put("source", measurement.getSource());
        view.put("availabilityPercent", measurement.getAvailabilityPercent());
        view.put("budgetRemainingPercent", snapshot.budgetRemainingPercent());
        return view;
    }

    private SloDefinition copyDefinition(SloDefinition source) {
        SloDefinition copy = new SloDefinition();
        copy.setId(source.getId()); copy.setTenantId(source.getTenantId()); copy.setName(source.getName());
        copy.setDescription(source.getDescription()); copy.setStatusComponentId(source.getStatusComponentId());
        copy.setTargetPercent(source.getTargetPercent()); copy.setWindowDays(source.getWindowDays());
        copy.setWarningBudgetPercent(source.getWarningBudgetPercent()); copy.setCriticalBudgetPercent(source.getCriticalBudgetPercent());
        copy.setEnabled(source.getEnabled()); copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt()); copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private BigDecimal scale(BigDecimal value, String field) {
        if (value == null) throw new BusinessException(field + "不能为空");
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private String required(String value, String field, int maxLength) {
        String result = optional(value, maxLength, field);
        if (result == null) throw new BusinessException(field + "不能为空");
        return result;
    }

    private String optional(String value, int maxLength, String field) {
        if (value == null) return null;
        String result = value.trim();
        if (result.isBlank()) return null;
        if (result.length() > maxLength) throw new BusinessException(field + "不能超过 " + maxLength + " 个字符");
        return result;
    }

    public record DefinitionCommand(String name, String description, Long statusComponentId, BigDecimal targetPercent,
                                    Integer windowDays, BigDecimal warningBudgetPercent,
                                    BigDecimal criticalBudgetPercent, Boolean enabled) { }

    public record MeasurementCommand(Long totalRequests, Long goodRequests, Long sourceAlertId,
                                     LocalDateTime occurredAt, String note) { }

    public record ReliabilitySnapshot(Long definitionId, Integer windowDays, LocalDateTime windowStart,
                                      boolean dataAvailable, long sampleCount, long totalRequests, long badRequests,
                                      BigDecimal availabilityPercent, BigDecimal targetPercent,
                                      BigDecimal errorBudgetConsumedPercent, BigDecimal budgetRemainingPercent,
                                      SloBreachLevel currentLevel) { }

    public record DefinitionView(SloDefinition definition, String componentName, ReliabilitySnapshot snapshot) { }

    public record BreachView(SloBreachEvent breach, String definitionName) { }

    public record ReliabilityCenterView(List<DefinitionView> definitions, List<BreachView> breaches,
                                        List<StatusPageComponent> components) { }

    public record DefinitionDetail(SloDefinition definition, String componentName, ReliabilitySnapshot snapshot,
                                   List<SloMeasurement> measurements, List<SloBreachEvent> breaches) { }

    public record MeasurementResult(SloMeasurement measurement, ReliabilitySnapshot snapshot, SloBreachEvent breach) { }
}
