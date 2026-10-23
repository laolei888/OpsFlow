package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.messaging.AsyncNotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.ToLongFunction;

@Service
@RequiredArgsConstructor
public class CapacityAlertService {
    private final CapacityAlertMapper alertMapper;
    private final CapacitySnapshotMapper snapshotMapper;
    private final CapacityProperties properties;
    private final AuthService authService;
    private final AsyncNotificationPublisher notificationPublisher;
    private final AuditLogService auditLogService;

    @Transactional
    public void evaluate(CapacitySnapshot snapshot) {
        CapacitySnapshot previous = snapshotMapper.selectOne(new LambdaQueryWrapper<CapacitySnapshot>()
                .eq(CapacitySnapshot::getTenantId, snapshot.getTenantId())
                .lt(CapacitySnapshot::getCapturedAt, snapshot.getCapturedAt())
                .orderByDesc(CapacitySnapshot::getCapturedAt)
                .orderByDesc(CapacitySnapshot::getId)
                .last("LIMIT 1"));
        for (AlertMetric metric : metrics()) evaluateMetric(snapshot, previous, metric);
    }

    public List<CapacityAlert> list(Long tenantId, CapacityAlertStatus status,
                                    CapacityAlertLevel level, Integer requestedLimit) {
        int limit = requestedLimit == null ? 100 : Math.max(1, Math.min(requestedLimit, 300));
        LambdaQueryWrapper<CapacityAlert> query = new LambdaQueryWrapper<CapacityAlert>()
                .eq(CapacityAlert::getTenantId, tenantId)
                .last("ORDER BY FIELD(status, 'OPEN', 'ACKNOWLEDGED', 'RESOLVED'), "
                        + "FIELD(level, 'CRITICAL', 'WARNING', 'WATCH'), updated_at DESC LIMIT " + limit);
        if (status != null) query.eq(CapacityAlert::getStatus, status);
        if (level != null) query.eq(CapacityAlert::getLevel, level);
        return alertMapper.selectList(query);
    }

    public AlertSummary summary(Long tenantId) {
        List<CapacityAlert> alerts = alertMapper.selectList(new LambdaQueryWrapper<CapacityAlert>()
                .eq(CapacityAlert::getTenantId, tenantId)
                .in(CapacityAlert::getStatus, List.of(CapacityAlertStatus.OPEN, CapacityAlertStatus.ACKNOWLEDGED)));
        return new AlertSummary(
                alerts.stream().filter(alert -> alert.getStatus() == CapacityAlertStatus.OPEN).count(),
                alerts.stream().filter(alert -> alert.getStatus() == CapacityAlertStatus.ACKNOWLEDGED).count(),
                alerts.stream().filter(alert -> alert.getLevel() == CapacityAlertLevel.WATCH).count(),
                alerts.stream().filter(alert -> alert.getLevel() == CapacityAlertLevel.WARNING).count(),
                alerts.stream().filter(alert -> alert.getLevel() == CapacityAlertLevel.CRITICAL).count(),
                alerts.stream().map(CapacityAlert::getFirstDetectedAt).min(LocalDateTime::compareTo).orElse(null));
    }

    @Transactional
    public CapacityAlert acknowledge(Long id, String note, Long tenantId, Long actorId) {
        CapacityAlert alert = required(id, tenantId);
        if (alert.getStatus() == CapacityAlertStatus.RESOLVED) {
            throw new BusinessException("已解决的容量预警不能再次确认");
        }
        if (alert.getStatus() == CapacityAlertStatus.ACKNOWLEDGED) return alert;
        alert.setStatus(CapacityAlertStatus.ACKNOWLEDGED);
        alert.setAcknowledgedBy(actorId);
        alert.setAcknowledgedAt(LocalDateTime.now());
        alert.setAcknowledgementNote(optional(note, "确认说明"));
        alert.setUpdatedAt(LocalDateTime.now());
        alertMapper.updateById(alert);
        auditLogService.record(tenantId, actorId, "CAPACITY_ALERT", id, "CAPACITY_ALERT_ACKNOWLEDGED",
                CapacityAlertStatus.OPEN, CapacityAlertStatus.ACKNOWLEDGED, "确认容量预警：" + alert.getMetricLabel());
        return alert;
    }

    @Transactional
    public CapacityAlert resolve(Long id, String note, Long tenantId, Long actorId) {
        CapacityAlert alert = required(id, tenantId);
        if (alert.getStatus() == CapacityAlertStatus.RESOLVED) return alert;
        String normalizedNote = required(note, "解决说明");
        CapacityAlertStatus before = alert.getStatus();
        LocalDateTime now = LocalDateTime.now();
        alert.setStatus(CapacityAlertStatus.RESOLVED);
        alert.setResolvedBy(actorId);
        alert.setResolvedAt(now);
        alert.setResolutionNote(normalizedNote);
        alert.setActiveKey(null);
        alert.setUpdatedAt(now);
        alertMapper.updateById(alert);
        auditLogService.record(tenantId, actorId, "CAPACITY_ALERT", id, "CAPACITY_ALERT_RESOLVED",
                before, CapacityAlertStatus.RESOLVED, "解决容量预警：" + normalizedNote);
        return alert;
    }

    private void evaluateMetric(CapacitySnapshot snapshot, CapacitySnapshot previous, AlertMetric metric) {
        long current = metric.reader().applyAsLong(snapshot);
        Growth growth = growth(metric, snapshot, previous);
        CapacityAlertLevel level = determineLevel(current, metric.threshold(), growth.projectedDays());
        String activeKey = snapshot.getTenantId() + ":" + metric.key();
        CapacityAlert existing = alertMapper.selectOne(new LambdaQueryWrapper<CapacityAlert>()
                .eq(CapacityAlert::getActiveKey, activeKey).last("LIMIT 1"));
        if (level == null) {
            if (existing != null) autoResolve(existing, snapshot, metric, current, growth);
            return;
        }
        if (existing == null) create(snapshot, metric, current, growth, level, activeKey);
        else update(existing, snapshot, metric, current, growth, level);
    }

    private void create(CapacitySnapshot snapshot, AlertMetric metric, long current, Growth growth,
                        CapacityAlertLevel level, String activeKey) {
        LocalDateTime now = LocalDateTime.now();
        CapacityAlert alert = new CapacityAlert();
        alert.setTenantId(snapshot.getTenantId());
        alert.setAlertNo("CAP-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase(Locale.ROOT));
        alert.setMetricKey(metric.key());
        alert.setMetricLabel(metric.label());
        alert.setLevel(level);
        alert.setStatus(CapacityAlertStatus.OPEN);
        applyMeasurement(alert, snapshot, metric, current, growth);
        alert.setOccurrenceCount(1);
        alert.setFirstDetectedAt(now);
        alert.setLastDetectedAt(now);
        alert.setActiveKey(activeKey);
        alert.setCreatedAt(now);
        alert.setUpdatedAt(now);
        alertMapper.insert(alert);
        auditLogService.record(snapshot.getTenantId(), null, "CAPACITY_ALERT", alert.getId(),
                "CAPACITY_ALERT_CREATED", null, alert, alertDetail(alert));
        notifyAdmins(alert);
    }

    private void update(CapacityAlert alert, CapacitySnapshot snapshot, AlertMetric metric, long current,
                        Growth growth, CapacityAlertLevel level) {
        CapacityAlertLevel beforeLevel = alert.getLevel();
        CapacityAlertStatus beforeStatus = alert.getStatus();
        boolean escalated = rank(level) > rank(beforeLevel);
        applyMeasurement(alert, snapshot, metric, current, growth);
        alert.setLevel(level);
        alert.setOccurrenceCount((alert.getOccurrenceCount() == null ? 0 : alert.getOccurrenceCount()) + 1);
        alert.setLastDetectedAt(LocalDateTime.now());
        alert.setUpdatedAt(LocalDateTime.now());
        if (escalated && beforeStatus == CapacityAlertStatus.ACKNOWLEDGED) {
            alert.setStatus(CapacityAlertStatus.OPEN);
            alert.setAcknowledgedBy(null);
            alert.setAcknowledgedAt(null);
            alert.setAcknowledgementNote(null);
        }
        alertMapper.updateById(alert);
        if (escalated) {
            auditLogService.record(snapshot.getTenantId(), null, "CAPACITY_ALERT", alert.getId(),
                    "CAPACITY_ALERT_ESCALATED", beforeLevel, level, alertDetail(alert));
            notifyAdmins(alert);
        }
    }

    private void autoResolve(CapacityAlert alert, CapacitySnapshot snapshot, AlertMetric metric,
                             long current, Growth growth) {
        CapacityAlertStatus before = alert.getStatus();
        LocalDateTime now = LocalDateTime.now();
        applyMeasurement(alert, snapshot, metric, current, growth);
        alert.setStatus(CapacityAlertStatus.RESOLVED);
        alert.setResolvedAt(now);
        alert.setResolutionNote("指标已恢复到安全范围");
        alert.setActiveKey(null);
        alert.setUpdatedAt(now);
        alertMapper.updateById(alert);
        auditLogService.record(snapshot.getTenantId(), null, "CAPACITY_ALERT", alert.getId(),
                "CAPACITY_ALERT_AUTO_RESOLVED", before, CapacityAlertStatus.RESOLVED,
                "容量指标自动恢复：" + alert.getMetricLabel());
    }

    private void applyMeasurement(CapacityAlert alert, CapacitySnapshot snapshot, AlertMetric metric,
                                  long current, Growth growth) {
        alert.setCurrentValue(current);
        alert.setThresholdValue(metric.threshold());
        alert.setUtilizationPercent(metric.threshold() <= 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : decimal(current * 100.0 / metric.threshold()));
        alert.setDailyGrowth(decimal(growth.dailyGrowth()));
        alert.setProjectedDays(growth.projectedDays());
        alert.setSnapshotId(snapshot.getId());
    }

    private Growth growth(AlertMetric metric, CapacitySnapshot current, CapacitySnapshot previous) {
        if (previous == null) return new Growth(0, null);
        double days = Math.max(Duration.between(previous.getCapturedAt(), current.getCapturedAt()).toMinutes() / 1440.0,
                1.0 / 24.0);
        long before = metric.reader().applyAsLong(previous);
        long now = metric.reader().applyAsLong(current);
        double daily = (now - before) / days;
        Long projected = daily <= 0 || now >= metric.threshold() ? null
                : Math.max(0, (long) Math.ceil((metric.threshold() - now) / daily));
        return new Growth(daily, projected);
    }

    private CapacityAlertLevel determineLevel(long current, long threshold, Long projectedDays) {
        if (threshold <= 0) return null;
        if (current >= threshold) return CapacityAlertLevel.CRITICAL;
        if (current >= threshold * 0.8) return CapacityAlertLevel.WARNING;
        if (projectedDays != null && projectedDays <= 30) return CapacityAlertLevel.WATCH;
        return null;
    }

    private void notifyAdmins(CapacityAlert alert) {
        String title = alert.getLevel() == CapacityAlertLevel.CRITICAL ? "容量阈值已触顶" : "容量风险预警";
        String content = alert.getMetricLabel() + "当前 " + alert.getCurrentValue() + "，阈值 "
                + alert.getThresholdValue() + "，利用率 " + alert.getUtilizationPercent() + "%"
                + (alert.getProjectedDays() == null ? "" : "，预计 " + alert.getProjectedDays() + " 天后触顶");
        authService.staffMembers(alert.getTenantId()).stream()
                .filter(staff -> staff.roles().contains("ADMIN"))
                .forEach(admin -> notificationPublisher.enqueue(alert.getTenantId(), admin.id(), null,
                        "CAPACITY_THRESHOLD", title, content,
                        "CAPACITY_ALERT:" + alert.getId() + ":" + alert.getLevel(),
                        "CAPACITY_ALERT", alert.getId()));
    }

    private CapacityAlert required(Long id, Long tenantId) {
        CapacityAlert alert = alertMapper.selectOne(new LambdaQueryWrapper<CapacityAlert>()
                .eq(CapacityAlert::getId, id).eq(CapacityAlert::getTenantId, tenantId));
        if (alert == null) throw new BusinessException("容量预警不存在或不属于当前租户");
        return alert;
    }

    private List<AlertMetric> metrics() {
        CapacityProperties.Thresholds thresholds = properties.getThresholds();
        return List.of(
                new AlertMetric("activeTickets", "活跃工单", thresholds.getActiveTickets(), CapacitySnapshot::getActiveTickets),
                new AlertMetric("overdueTickets", "逾期工单", thresholds.getOverdueTickets(), CapacitySnapshot::getOverdueTickets),
                new AlertMetric("activeIncidents", "活跃事件", thresholds.getActiveIncidents(), CapacitySnapshot::getActiveIncidents),
                new AlertMetric("firingAlerts", "未恢复告警", thresholds.getFiringAlerts(), CapacitySnapshot::getFiringAlerts),
                new AlertMetric("asyncBacklog", "异步消息积压", thresholds.getAsyncBacklog(), CapacitySnapshot::getAsyncBacklog),
                new AlertMetric("attachmentBacklog", "附件扫描积压", thresholds.getAttachmentBacklog(), CapacitySnapshot::getAttachmentBacklog),
                new AlertMetric("attachmentBytes", "附件存储", thresholds.getAttachmentBytes(), CapacitySnapshot::getAttachmentBytes),
                new AlertMetric("databaseBytes", "数据库体积", thresholds.getDatabaseBytes(), CapacitySnapshot::getDatabaseBytes));
    }

    private int rank(CapacityAlertLevel level) {
        return switch (level) { case WATCH -> 1; case WARNING -> 2; case CRITICAL -> 3; };
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String alertDetail(CapacityAlert alert) {
        return alert.getMetricLabel() + "达到 " + alert.getLevel() + "，当前值 " + alert.getCurrentValue()
                + "，阈值 " + alert.getThresholdValue();
    }

    private String optional(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 500) throw new BusinessException(field + "不能超过 500 个字符");
        return normalized;
    }

    private String required(String value, String field) {
        String normalized = optional(value, field);
        if (normalized == null || normalized.length() < 5) throw new BusinessException(field + "至少需要 5 个字符");
        return normalized;
    }

    private record AlertMetric(String key, String label, long threshold,
                               ToLongFunction<CapacitySnapshot> reader) { }
    private record Growth(double dailyGrowth, Long projectedDays) { }
    public record AlertSummary(long open, long acknowledged, long watch, long warning,
                               long critical, LocalDateTime oldestActiveAt) { }
}
