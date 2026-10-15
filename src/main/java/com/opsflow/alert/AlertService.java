package com.opsflow.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.cmdb.CmdbAsset;
import com.opsflow.cmdb.CmdbAssetService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentService;
import com.opsflow.incident.IncidentSeverity;
import com.opsflow.notification.NotificationService;
import com.opsflow.oncall.OnCallRecipientResolver;
import com.opsflow.runbook.RunbookService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertService {
    private final MonitoringAlertMapper alertMapper;
    private final AlertEventMapper eventMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertSourceMapper sourceMapper;
    private final AlertConfigService configService;
    private final CmdbAssetService cmdbAssetService;
    private final TicketService ticketService;
    private final IncidentService incidentService;
    private final NotificationService notificationService;
    private final OnCallRecipientResolver onCallRecipientResolver;
    private final RunbookService runbookService;
    private final AuthService authService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public List<MonitoringAlert> list(Long tenantId, AlertStatus status, AlertSeverity severity,
                                      Long sourceId, String keyword, Integer limit) {
        int safeLimit = limit == null ? 150 : Math.max(1, Math.min(limit, 300));
        LambdaQueryWrapper<MonitoringAlert> query = new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getTenantId, tenantId)
                .orderByAsc(MonitoringAlert::getStatus)
                .orderByAsc(MonitoringAlert::getSeverity)
                .orderByDesc(MonitoringAlert::getLastSeenAt)
                .orderByDesc(MonitoringAlert::getId)
                .last("LIMIT " + safeLimit);
        if (status != null) query.eq(MonitoringAlert::getStatus, status);
        if (severity != null) query.eq(MonitoringAlert::getSeverity, severity);
        if (sourceId != null) query.eq(MonitoringAlert::getSourceId, sourceId);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(MonitoringAlert::getAlertNo, search)
                    .or().like(MonitoringAlert::getTitle, search)
                    .or().like(MonitoringAlert::getDescription, search)
                    .or().like(MonitoringAlert::getFingerprint, search)
                    .or().like(MonitoringAlert::getExternalAlertId, search));
        }
        return alertMapper.selectList(query);
    }

    public AlertSummary summary(Long tenantId) {
        long active = alertMapper.selectCount(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getTenantId, tenantId)
                .in(MonitoringAlert::getStatus, AlertStatus.FIRING, AlertStatus.ACKNOWLEDGED));
        long unacknowledged = alertMapper.selectCount(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getTenantId, tenantId)
                .eq(MonitoringAlert::getStatus, AlertStatus.FIRING));
        long critical = alertMapper.selectCount(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getTenantId, tenantId)
                .in(MonitoringAlert::getStatus, AlertStatus.FIRING, AlertStatus.ACKNOWLEDGED)
                .eq(MonitoringAlert::getSeverity, AlertSeverity.CRITICAL));
        long suppressed = alertMapper.selectCount(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getTenantId, tenantId)
                .eq(MonitoringAlert::getStatus, AlertStatus.SUPPRESSED));
        long resolvedLast24Hours = alertMapper.selectCount(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getTenantId, tenantId)
                .eq(MonitoringAlert::getStatus, AlertStatus.RESOLVED)
                .ge(MonitoringAlert::getResolvedAt, LocalDateTime.now().minusHours(24)));
        return new AlertSummary(active, unacknowledged, critical, suppressed, resolvedLast24Hours);
    }

    public AlertDetail detail(Long id, Long tenantId) {
        MonitoringAlert alert = find(id, tenantId);
        AlertSource source = sourceMapper.selectOne(new LambdaQueryWrapper<AlertSource>()
                .eq(AlertSource::getId, alert.getSourceId())
                .eq(AlertSource::getTenantId, tenantId));
        AlertRule rule = alert.getRuleId() == null ? null : ruleMapper.selectOne(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getId, alert.getRuleId())
                .eq(AlertRule::getTenantId, tenantId));
        CmdbAsset asset = alert.getAssetId() == null ? null : cmdbAssetService.get(alert.getAssetId(), tenantId);
        List<AlertEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AlertEvent>()
                .eq(AlertEvent::getTenantId, tenantId)
                .eq(AlertEvent::getAlertId, id)
                .orderByAsc(AlertEvent::getCreatedAt)
                .orderByAsc(AlertEvent::getId));
        return new AlertDetail(alert, source, rule, asset, events,
                runbookService.recommendationsForAlert(alert.getId(), tenantId));
    }

    @Transactional
    public IngestResult ingest(String sourceKey, String secret, IngestCommand command) {
        AlertSource source = configService.authenticate(sourceKey, secret);
        validateIngestCommand(command);
        LocalDateTime now = LocalDateTime.now();
        Long tenantId = source.getTenantId();
        Long assetId = command.assetId() == null ? source.getDefaultAssetId() : command.assetId();
        if (assetId != null) cmdbAssetService.get(assetId, tenantId);
        String fingerprint = normalize(command.fingerprint());
        String labelsJson = toJson(command.labels());
        AlertSignalState signalState = command.state() == null ? AlertSignalState.FIRING : command.state();
        AlertSilence silence = signalState == AlertSignalState.FIRING
                ? configService.activeSilence(tenantId, source.getId(), assetId, fingerprint, now)
                : null;
        MonitoringAlert alert = alertMapper.selectByFingerprintForUpdate(tenantId, source.getId(), fingerprint);
        boolean created = alert == null;
        boolean aggregated = !created;
        AlertRuleAction appliedAction = null;

        if (created) {
            alert = newAlert(source, command, fingerprint, labelsJson, assetId, signalState, silence, now);
            alertMapper.insert(alert);
            if (signalState == AlertSignalState.RESOLVED) {
                createEvent(alert, AlertEventType.RESOLVED, "接收到已恢复状态，记录为已解决告警", labelsJson,
                        source.getCreatedBy(), now);
                auditLogService.record(tenantId, source.getCreatedBy(), "MONITORING_ALERT", alert.getId(),
                        "ALERT_RESOLVED", null, alert, "外部监控上报恢复状态");
            } else if (silence != null) {
                createEvent(alert, AlertEventType.SUPPRESSED,
                        "命中静默窗口「" + silence.getName() + "」：" + silence.getReason(), labelsJson,
                        source.getCreatedBy(), now);
                auditLogService.record(tenantId, source.getCreatedBy(), "MONITORING_ALERT", alert.getId(),
                        "ALERT_SUPPRESSED", null, alert, "新告警命中静默窗口");
            } else {
                createEvent(alert, AlertEventType.TRIGGERED, "首次接收到告警", labelsJson,
                        source.getCreatedBy(), now);
                auditLogService.record(tenantId, source.getCreatedBy(), "MONITORING_ALERT", alert.getId(),
                        "ALERT_TRIGGERED", null, alert, "外部监控触发新告警");
                appliedAction = applyRule(alert, source, source.getCreatedBy(), now);
            }
        } else {
            AlertSeverity previousSeverity = alert.getSeverity();
            AlertStatus previousStatus = alert.getStatus();
            updateOccurrence(alert, command, labelsJson, assetId, now);
            if (signalState == AlertSignalState.RESOLVED) {
                if (previousStatus == AlertStatus.RESOLVED) {
                    createEvent(alert, AlertEventType.REPEATED, "重复接收到恢复状态", labelsJson,
                            source.getCreatedBy(), now);
                } else {
                    markResolved(alert, source.getCreatedBy(), now);
                    createEvent(alert, AlertEventType.RESOLVED, "外部监控上报告警已恢复", labelsJson,
                            source.getCreatedBy(), now);
                    auditLogService.record(tenantId, source.getCreatedBy(), "MONITORING_ALERT", alert.getId(),
                            "ALERT_RESOLVED", previousStatus, alert, "外部监控上报告警恢复");
                }
                alertMapper.updateById(alert);
            } else if (silence != null) {
                if (previousStatus != AlertStatus.SUPPRESSED) {
                    if (previousStatus == AlertStatus.RESOLVED) incrementGeneration(alert);
                    alert.setStatus(AlertStatus.SUPPRESSED);
                    clearAcknowledgement(alert);
                    clearResolution(alert);
                    createEvent(alert, AlertEventType.SUPPRESSED,
                            "命中静默窗口「" + silence.getName() + "」：" + silence.getReason(), labelsJson,
                            source.getCreatedBy(), now);
                    auditLogService.record(tenantId, source.getCreatedBy(), "MONITORING_ALERT", alert.getId(),
                            "ALERT_SUPPRESSED", previousStatus, alert, "聚合告警命中静默窗口");
                } else {
                    createEvent(alert, AlertEventType.REPEATED, "静默期间再次收到相同告警", labelsJson,
                            source.getCreatedBy(), now);
                }
                alertMapper.updateById(alert);
            } else if (previousStatus == AlertStatus.RESOLVED || previousStatus == AlertStatus.SUPPRESSED) {
                incrementGeneration(alert);
                alert.setStatus(AlertStatus.FIRING);
                clearAcknowledgement(alert);
                clearResolution(alert);
                clearAutomationLinks(alert);
                alertMapper.updateById(alert);
                createEvent(alert, AlertEventType.REOPENED, "告警再次触发，开始新的处理周期", labelsJson,
                        source.getCreatedBy(), now);
                auditLogService.record(tenantId, source.getCreatedBy(), "MONITORING_ALERT", alert.getId(),
                        "ALERT_REOPENED", previousStatus, alert, "外部监控重新触发已结束告警");
                appliedAction = applyRule(alert, source, source.getCreatedBy(), now);
            } else if (isEscalated(previousSeverity, command.severity())) {
                incrementGeneration(alert);
                alert.setStatus(AlertStatus.FIRING);
                clearAcknowledgement(alert);
                clearResolution(alert);
                alert.setRuleId(null);
                alertMapper.updateById(alert);
                createEvent(alert, AlertEventType.REOPENED,
                        "告警严重度从 " + previousSeverity + " 升级为 " + command.severity(), labelsJson,
                        source.getCreatedBy(), now);
                auditLogService.record(tenantId, source.getCreatedBy(), "MONITORING_ALERT", alert.getId(),
                        "ALERT_SEVERITY_ESCALATED", previousSeverity, alert, "聚合告警严重度升级");
                appliedAction = applyRule(alert, source, source.getCreatedBy(), now);
            } else {
                alertMapper.updateById(alert);
                createEvent(alert, AlertEventType.REPEATED,
                        "相同指纹告警第 " + alert.getOccurrenceCount() + " 次出现", labelsJson,
                        source.getCreatedBy(), now);
            }
        }
        configService.markReceived(source, now);
        return new IngestResult(alert, created, aggregated, appliedAction);
    }

    @Transactional
    public MonitoringAlert acknowledge(Long id, Long actorId, Long tenantId) {
        MonitoringAlert alert = lock(id, tenantId);
        if (alert.getStatus() != AlertStatus.FIRING) throw new BusinessException("只有待确认告警可以执行确认");
        AlertStatus before = alert.getStatus();
        alert.setStatus(AlertStatus.ACKNOWLEDGED);
        alert.setAcknowledgedBy(actorId);
        alert.setAcknowledgedAt(LocalDateTime.now());
        alert.setUpdatedAt(LocalDateTime.now());
        alertMapper.updateById(alert);
        createEvent(alert, AlertEventType.ACKNOWLEDGED, "告警已由客服确认并开始处理", null, actorId,
                LocalDateTime.now());
        auditLogService.record(tenantId, actorId, "MONITORING_ALERT", id, "ALERT_ACKNOWLEDGED",
                before, alert, "确认监控告警");
        return alert;
    }

    @Transactional
    public MonitoringAlert suppress(Long id, String reason, Long actorId, Long tenantId) {
        String normalizedReason = normalize(reason);
        if (normalizedReason == null || normalizedReason.length() > 1000) {
            throw new BusinessException("抑制原因不能为空且不能超过 1000 个字符");
        }
        MonitoringAlert alert = lock(id, tenantId);
        if (alert.getStatus() == AlertStatus.RESOLVED) throw new BusinessException("已解决告警无需抑制");
        AlertStatus before = alert.getStatus();
        alert.setStatus(AlertStatus.SUPPRESSED);
        alert.setUpdatedAt(LocalDateTime.now());
        alertMapper.updateById(alert);
        createEvent(alert, AlertEventType.SUPPRESSED, "人工抑制：" + normalizedReason, null, actorId,
                LocalDateTime.now());
        auditLogService.record(tenantId, actorId, "MONITORING_ALERT", id, "ALERT_SUPPRESSED",
                before, alert, "人工抑制监控告警：" + normalizedReason);
        return alert;
    }

    @Transactional
    public MonitoringAlert resolve(Long id, String note, Long actorId, Long tenantId) {
        MonitoringAlert alert = lock(id, tenantId);
        if (alert.getStatus() == AlertStatus.RESOLVED) return alert;
        AlertStatus before = alert.getStatus();
        markResolved(alert, actorId, LocalDateTime.now());
        alertMapper.updateById(alert);
        String normalizedNote = normalize(note);
        createEvent(alert, AlertEventType.RESOLVED,
                normalizedNote == null ? "客服手动标记告警已解决" : "客服解决告警：" + truncate(normalizedNote, 900),
                null, actorId, LocalDateTime.now());
        auditLogService.record(tenantId, actorId, "MONITORING_ALERT", id, "ALERT_RESOLVED",
                before, alert, "手动解决监控告警");
        return alert;
    }

    @Transactional
    public MonitoringAlert reopen(Long id, String note, Long actorId, Long tenantId) {
        MonitoringAlert alert = lock(id, tenantId);
        if (alert.getStatus() != AlertStatus.RESOLVED && alert.getStatus() != AlertStatus.SUPPRESSED) {
            throw new BusinessException("只有已解决或已抑制告警可以重新打开");
        }
        AlertStatus before = alert.getStatus();
        incrementGeneration(alert);
        alert.setStatus(AlertStatus.FIRING);
        clearAcknowledgement(alert);
        clearResolution(alert);
        clearAutomationLinks(alert);
        alert.setUpdatedAt(LocalDateTime.now());
        alertMapper.updateById(alert);
        String normalizedNote = normalize(note);
        createEvent(alert, AlertEventType.REOPENED,
                normalizedNote == null ? "客服重新打开告警" : "客服重新打开告警：" + truncate(normalizedNote, 900),
                null, actorId, LocalDateTime.now());
        auditLogService.record(tenantId, actorId, "MONITORING_ALERT", id, "ALERT_REOPENED",
                before, alert, "重新打开监控告警");
        AlertSource source = configService.findSource(alert.getSourceId(), tenantId);
        applyRule(alert, source, actorId, LocalDateTime.now());
        return alert;
    }

    private AlertRuleAction applyRule(MonitoringAlert alert, AlertSource source, Long actorId, LocalDateTime now) {
        AlertRule rule = configService.matchRule(alert.getTenantId(), source.getId(), alert.getSeverity());
        if (rule == null) return null;
        alert.setRuleId(rule.getId());
        alert.setUpdatedAt(now);
        alertMapper.updateById(alert);
        createEvent(alert, AlertEventType.RULE_MATCHED,
                "命中规则「" + rule.getName() + "」，执行 " + rule.getAction(), null, actorId, now);

        if (rule.getAction() == AlertRuleAction.CREATE_TICKET && alert.getTicketId() == null) {
            Ticket ticket = createTicket(alert, source, actorId);
            alert.setTicketId(ticket.getId());
            createEvent(alert, AlertEventType.TICKET_CREATED,
                    "自动创建工单 " + ticket.getTicketNo(), null, actorId, LocalDateTime.now());
        } else if (rule.getAction() == AlertRuleAction.CREATE_INCIDENT && alert.getIncidentId() == null) {
            Incident incident = createIncident(alert, source, actorId);
            alert.setIncidentId(incident.getId());
            createEvent(alert, AlertEventType.INCIDENT_CREATED,
                    "自动宣布重大事件 " + incident.getIncidentNo(), null, actorId, LocalDateTime.now());
        }
        alert.setUpdatedAt(LocalDateTime.now());
        alertMapper.updateById(alert);
        notifyOnCall(alert, source, rule, now);
        auditLogService.record(alert.getTenantId(), actorId, "MONITORING_ALERT", alert.getId(),
                "ALERT_RULE_APPLIED", null, rule, "告警命中自动化规则并执行 " + rule.getAction());
        return rule.getAction();
    }

    private Ticket createTicket(MonitoringAlert alert, AlertSource source, Long actorId) {
        Ticket ticket = new Ticket();
        ticket.setTitle(truncate("[监控告警] " + alert.getTitle(), 160));
        ticket.setDescription(buildDescription(alert, source));
        ticket.setCategory("监控告警");
        ticket.setPriority(toTicketPriority(alert.getSeverity()));
        Ticket created = ticketService.create(ticket, actorId, alert.getTenantId(),
                "monitor-alert-ticket-" + alert.getId() + "-" + alert.getGeneration());
        if (alert.getAssetId() != null) {
            cmdbAssetService.linkTicketAsset(created.getId(),
                    new CmdbAssetService.TicketAssetCommand(alert.getAssetId(), "AFFECTED",
                            "由监控告警 " + alert.getAlertNo() + " 自动关联"),
                    alert.getTenantId(), actorId);
        }
        return created;
    }

    private Incident createIncident(MonitoringAlert alert, AlertSource source, Long preferredActorId) {
        Long actorId = staffActor(alert.getTenantId(), preferredActorId);
        Incident incident = incidentService.create(new IncidentService.IncidentCommand(
                        truncate("[监控告警] " + alert.getTitle(), 160),
                        buildDescription(alert, source),
                        toIncidentSeverity(alert.getSeverity()),
                        actorId,
                        "监控系统检测到生产服务异常；告警编号 " + alert.getAlertNo(),
                        truncate(alert.getDescription(), 1000),
                        "OpsFlow Alert Center / " + source.getName(),
                        List.of("确认告警真实性与影响范围", "执行止损并持续更新事件动态")),
                "monitor-alert-incident-" + alert.getId() + "-" + alert.getGeneration(),
                actorId, alert.getTenantId());
        if (alert.getAssetId() != null) {
            incidentService.linkAsset(incident.getId(),
                    new IncidentService.LinkCommand(alert.getAssetId(), "IMPACTED",
                            "由监控告警 " + alert.getAlertNo() + " 自动关联"),
                    actorId, alert.getTenantId());
        }
        return incident;
    }

    private void notifyOnCall(MonitoringAlert alert, AlertSource source, AlertRule rule, LocalDateTime now) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (rule.getScheduleId() != null) {
            recipients.addAll(onCallRecipientResolver.activeRecipients(alert.getTenantId(), rule.getScheduleId(), now));
        } else {
            recipients.addAll(onCallRecipientResolver.activePrimaryRecipients(alert.getTenantId(), now));
        }
        if (recipients.isEmpty()) {
            authService.staffMembers(alert.getTenantId()).forEach(member -> recipients.add(member.id()));
        }
        String link = alert.getIncidentId() != null ? "，已升级为重大事件 #" + alert.getIncidentId()
                : alert.getTicketId() != null ? "，已创建工单 #" + alert.getTicketId() : "";
        for (Long recipientId : recipients) {
            notificationService.notifyIfAbsent(alert.getTenantId(), recipientId, "MONITORING_ALERT",
                    "监控告警 · " + alert.getSeverity(),
                    "「" + alert.getTitle() + "」来自 " + source.getName() + link,
                    "MONITORING_ALERT:" + alert.getId() + ":" + alert.getGeneration() + ":" + recipientId);
        }
    }

    private MonitoringAlert newAlert(AlertSource source, IngestCommand command, String fingerprint,
                                      String labelsJson, Long assetId, AlertSignalState state,
                                      AlertSilence silence, LocalDateTime now) {
        MonitoringAlert alert = new MonitoringAlert();
        alert.setAlertNo("ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        alert.setTenantId(source.getTenantId());
        alert.setSourceId(source.getId());
        alert.setFingerprint(fingerprint);
        alert.setExternalAlertId(normalize(command.externalAlertId()));
        alert.setTitle(normalize(command.title()));
        alert.setDescription(normalize(command.description()));
        alert.setSeverity(command.severity());
        alert.setAssetId(assetId);
        alert.setLabelsJson(labelsJson);
        alert.setOccurrenceCount(1);
        alert.setGeneration(1);
        alert.setFirstSeenAt(now);
        alert.setLastSeenAt(now);
        alert.setCreatedAt(now);
        alert.setUpdatedAt(now);
        if (state == AlertSignalState.RESOLVED) {
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setResolvedBy(source.getCreatedBy());
            alert.setResolvedAt(now);
        } else {
            alert.setStatus(silence == null ? AlertStatus.FIRING : AlertStatus.SUPPRESSED);
        }
        return alert;
    }

    private void updateOccurrence(MonitoringAlert alert, IngestCommand command, String labelsJson,
                                  Long assetId, LocalDateTime now) {
        alert.setExternalAlertId(normalize(command.externalAlertId()));
        alert.setTitle(normalize(command.title()));
        alert.setDescription(normalize(command.description()));
        alert.setSeverity(command.severity());
        alert.setAssetId(assetId);
        alert.setLabelsJson(labelsJson);
        alert.setOccurrenceCount((alert.getOccurrenceCount() == null ? 0 : alert.getOccurrenceCount()) + 1);
        alert.setLastSeenAt(now);
        alert.setUpdatedAt(now);
    }

    private void createEvent(MonitoringAlert alert, AlertEventType type, String message, String payloadJson,
                             Long actorId, LocalDateTime at) {
        AlertEvent event = new AlertEvent();
        event.setTenantId(alert.getTenantId());
        event.setAlertId(alert.getId());
        event.setEventType(type);
        event.setMessage(truncate(message, 1000));
        event.setPayloadJson(payloadJson);
        event.setActorId(actorId);
        event.setCreatedAt(at);
        eventMapper.insert(event);
    }

    private MonitoringAlert find(Long id, Long tenantId) {
        MonitoringAlert alert = alertMapper.selectOne(new LambdaQueryWrapper<MonitoringAlert>()
                .eq(MonitoringAlert::getId, id)
                .eq(MonitoringAlert::getTenantId, tenantId));
        if (alert == null) throw new BusinessException("监控告警不存在");
        return alert;
    }

    private MonitoringAlert lock(Long id, Long tenantId) {
        MonitoringAlert alert = alertMapper.selectForUpdate(id, tenantId);
        if (alert == null) throw new BusinessException("监控告警不存在");
        return alert;
    }

    private Long staffActor(Long tenantId, Long preferred) {
        List<StaffSummary> staff = authService.staffMembers(tenantId);
        return staff.stream().filter(item -> item.id().equals(preferred)).map(StaffSummary::id).findFirst()
                .orElseGet(() -> staff.stream().findFirst().map(StaffSummary::id)
                        .orElseThrow(() -> new BusinessException("当前租户没有可担任事件指挥人的客服账号")));
    }

    private void validateIngestCommand(IngestCommand command) {
        if (command == null) throw new BusinessException("告警内容不能为空");
        String fingerprint = normalize(command.fingerprint());
        String title = normalize(command.title());
        if (fingerprint == null || fingerprint.length() > 128) {
            throw new BusinessException("告警指纹不能为空且不能超过 128 个字符");
        }
        if (title == null || title.length() > 160) throw new BusinessException("告警标题不能为空且不能超过 160 个字符");
        if (command.severity() == null) throw new BusinessException("请选择告警严重度");
        if (command.description() != null && command.description().trim().length() > 10000) {
            throw new BusinessException("告警描述不能超过 10000 个字符");
        }
        if (command.externalAlertId() != null && command.externalAlertId().trim().length() > 128) {
            throw new BusinessException("外部告警编号不能超过 128 个字符");
        }
        if (command.labels() != null && command.labels().size() > 100) {
            throw new BusinessException("告警标签最多允许 100 项");
        }
    }

    private boolean isEscalated(AlertSeverity previous, AlertSeverity current) {
        return previous != null && current != null && current.ordinal() < previous.ordinal();
    }

    private void markResolved(MonitoringAlert alert, Long actorId, LocalDateTime now) {
        alert.setStatus(AlertStatus.RESOLVED);
        alert.setResolvedBy(actorId);
        alert.setResolvedAt(now);
        alert.setUpdatedAt(now);
    }

    private void clearAcknowledgement(MonitoringAlert alert) {
        alert.setAcknowledgedBy(null);
        alert.setAcknowledgedAt(null);
    }

    private void clearResolution(MonitoringAlert alert) {
        alert.setResolvedBy(null);
        alert.setResolvedAt(null);
    }

    private void clearAutomationLinks(MonitoringAlert alert) {
        alert.setRuleId(null);
        alert.setTicketId(null);
        alert.setIncidentId(null);
    }

    private void incrementGeneration(MonitoringAlert alert) {
        alert.setGeneration((alert.getGeneration() == null ? 0 : alert.getGeneration()) + 1);
    }

    private TicketPriority toTicketPriority(AlertSeverity severity) {
        return switch (severity) {
            case CRITICAL -> TicketPriority.URGENT;
            case HIGH -> TicketPriority.HIGH;
            case MEDIUM -> TicketPriority.MEDIUM;
            case LOW, INFO -> TicketPriority.LOW;
        };
    }

    private IncidentSeverity toIncidentSeverity(AlertSeverity severity) {
        return switch (severity) {
            case CRITICAL -> IncidentSeverity.SEV1;
            case HIGH -> IncidentSeverity.SEV2;
            case MEDIUM -> IncidentSeverity.SEV3;
            case LOW, INFO -> IncidentSeverity.SEV4;
        };
    }

    private String buildDescription(MonitoringAlert alert, AlertSource source) {
        StringBuilder content = new StringBuilder();
        content.append("告警编号：").append(alert.getAlertNo()).append('\n');
        content.append("告警来源：").append(source.getName()).append('\n');
        content.append("严重度：").append(alert.getSeverity()).append('\n');
        content.append("指纹：").append(alert.getFingerprint()).append('\n');
        if (alert.getExternalAlertId() != null) content.append("外部编号：").append(alert.getExternalAlertId()).append('\n');
        if (alert.getDescription() != null) content.append("\n").append(alert.getDescription());
        return truncate(content.toString(), 10000);
    }

    private String toJson(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(labels);
        } catch (JacksonException ex) {
            throw new BusinessException("告警标签序列化失败");
        }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    public record IngestCommand(String fingerprint, String externalAlertId, String title, String description,
                                AlertSeverity severity, AlertSignalState state, Long assetId,
                                Map<String, String> labels) { }
    public record IngestResult(MonitoringAlert alert, boolean created, boolean aggregated,
                               AlertRuleAction appliedAction) { }
    public record AlertDetail(MonitoringAlert alert, AlertSource source, AlertRule rule,
                              CmdbAsset asset, List<AlertEvent> events,
                              List<RunbookService.AlertRecommendation> runbookRecommendations) { }
    public record AlertSummary(long active, long unacknowledged, long critical, long suppressed,
                               long resolvedLast24Hours) { }
}
