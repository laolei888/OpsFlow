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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;

@Service
@RequiredArgsConstructor
public class CapacityAlertService {
    private final CapacityAlertMapper alertMapper;
    private final CapacitySnapshotMapper snapshotMapper;
    private final CapacityPolicyService policyService;
    private final CapacityMaintenanceWindowService maintenanceWindowService;
    private final AuthService authService;
    private final AsyncNotificationPublisher notificationPublisher;
    private final AuditLogService auditLogService;

    @Transactional
    public void evaluate(CapacitySnapshot snapshot) {
        CapacityPolicyService.EffectivePolicy policy = policyService.effective(snapshot.getTenantId());
        CapacityMaintenanceWindow maintenance = maintenanceWindowService.active(snapshot.getTenantId(), LocalDateTime.now());
        CapacitySnapshot previous = snapshotMapper.selectOne(new LambdaQueryWrapper<CapacitySnapshot>()
                .eq(CapacitySnapshot::getTenantId, snapshot.getTenantId())
                .lt(CapacitySnapshot::getCapturedAt, snapshot.getCapturedAt())
                .orderByDesc(CapacitySnapshot::getCapturedAt)
                .orderByDesc(CapacitySnapshot::getId)
                .last("LIMIT 1"));
        for (AlertMetric metric : metrics(policy)) evaluateMetric(snapshot, previous, metric, policy, maintenance);
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

    public AlertHistoryReport history(Long tenantId, Integer requestedDays) {
        int days = requestedDays == null ? 30 : Math.max(7, Math.min(requestedDays, 365));
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<CapacityAlert> alerts = alertMapper.selectList(new LambdaQueryWrapper<CapacityAlert>()
                .eq(CapacityAlert::getTenantId, tenantId)
                .ge(CapacityAlert::getCreatedAt, since)
                .orderByAsc(CapacityAlert::getCreatedAt));
        long resolved = alerts.stream().filter(alert -> alert.getStatus() == CapacityAlertStatus.RESOLVED).count();
        long suppressed = alerts.stream().filter(alert -> value(alert.getSuppressedNotificationCount()) > 0).count();
        double resolutionHours = alerts.stream()
                .filter(alert -> alert.getResolvedAt() != null && alert.getFirstDetectedAt() != null)
                .mapToLong(alert -> Duration.between(alert.getFirstDetectedAt(), alert.getResolvedAt()).toMinutes())
                .average().orElse(0) / 60.0;

        Map<LocalDate, MutableDaily> daily = new LinkedHashMap<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate date = LocalDate.now().minusDays(offset);
            daily.put(date, new MutableDaily(date));
        }
        Map<String, MutableMetric> metrics = new LinkedHashMap<>();
        for (CapacityAlert alert : alerts) {
            MutableDaily bucket = daily.get(alert.getCreatedAt().toLocalDate());
            if (bucket != null) bucket.add(alert);
            metrics.computeIfAbsent(alert.getMetricKey(), key -> new MutableMetric(key, alert.getMetricLabel())).add(alert);
        }
        return new AlertHistoryReport(days, alerts.size(), resolved, suppressed,
                percent(resolved, alerts.size()), decimal(resolutionHours),
                daily.values().stream().map(MutableDaily::view).toList(),
                metrics.values().stream().map(MutableMetric::view).toList());
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
        clearSuppression(alert);
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
        clearSuppression(alert);
        alert.setUpdatedAt(now);
        alertMapper.updateById(alert);
        auditLogService.record(tenantId, actorId, "CAPACITY_ALERT", id, "CAPACITY_ALERT_RESOLVED",
                before, CapacityAlertStatus.RESOLVED, "解决容量预警：" + normalizedNote);
        return alert;
    }

    private void evaluateMetric(CapacitySnapshot snapshot, CapacitySnapshot previous, AlertMetric metric,
                                CapacityPolicyService.EffectivePolicy policy,
                                CapacityMaintenanceWindow maintenance) {
        long current = metric.reader().applyAsLong(snapshot);
        Growth growth = growth(metric, snapshot, previous);
        CapacityAlertLevel level = determineLevel(current, metric.threshold(), growth.projectedDays(), policy);
        String activeKey = snapshot.getTenantId() + ":" + metric.key();
        CapacityAlert existing = alertMapper.selectOne(new LambdaQueryWrapper<CapacityAlert>()
                .eq(CapacityAlert::getActiveKey, activeKey).last("LIMIT 1"));
        if (level == null) {
            if (existing != null) autoResolve(existing, snapshot, metric, current, growth);
            return;
        }
        if (existing == null) create(snapshot, metric, current, growth, level, activeKey, policy, maintenance);
        else update(existing, snapshot, metric, current, growth, level, policy, maintenance);
    }

    private void create(CapacitySnapshot snapshot, AlertMetric metric, long current, Growth growth,
                        CapacityAlertLevel level, String activeKey,
                        CapacityPolicyService.EffectivePolicy policy,
                        CapacityMaintenanceWindow maintenance) {
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
        alert.setPolicyId(policy.id());
        alert.setOccurrenceCount(1);
        alert.setNotificationSuppressed(false);
        alert.setSuppressedNotificationCount(0);
        if (maintenance != null) applySuppression(alert, maintenance);
        alert.setFirstDetectedAt(now);
        alert.setLastDetectedAt(now);
        alert.setActiveKey(activeKey);
        alert.setCreatedAt(now);
        alert.setUpdatedAt(now);
        alertMapper.insert(alert);
        auditLogService.record(snapshot.getTenantId(), null, "CAPACITY_ALERT", alert.getId(),
                "CAPACITY_ALERT_CREATED", null, alert, alertDetail(alert));
        if (maintenance == null) notifyAdmins(alert);
        else auditSuppressed(alert, maintenance);
    }

    private void update(CapacityAlert alert, CapacitySnapshot snapshot, AlertMetric metric, long current,
                        Growth growth, CapacityAlertLevel level,
                        CapacityPolicyService.EffectivePolicy policy,
                        CapacityMaintenanceWindow maintenance) {
        CapacityAlertLevel beforeLevel = alert.getLevel();
        CapacityAlertStatus beforeStatus = alert.getStatus();
        boolean wasSuppressed = Boolean.TRUE.equals(alert.getNotificationSuppressed());
        boolean escalated = rank(level) > rank(beforeLevel);
        applyMeasurement(alert, snapshot, metric, current, growth);
        alert.setPolicyId(policy.id());
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
        boolean released = !escalated && wasSuppressed && maintenance == null;
        if (escalated && maintenance != null) applySuppression(alert, maintenance);
        else if ((escalated && maintenance == null) || released) clearSuppression(alert);
        alertMapper.updateById(alert);
        if (escalated) {
            auditLogService.record(snapshot.getTenantId(), null, "CAPACITY_ALERT", alert.getId(),
                    "CAPACITY_ALERT_ESCALATED", beforeLevel, level, alertDetail(alert));
            if (maintenance == null) notifyAdmins(alert);
            else auditSuppressed(alert, maintenance);
        } else if (released) {
            auditLogService.record(snapshot.getTenantId(), null, "CAPACITY_ALERT", alert.getId(),
                    "CAPACITY_ALERT_NOTIFICATION_RELEASED", true, false,
                    "维护窗口结束，恢复容量预警通知：" + alert.getMetricLabel());
            if (alert.getStatus() == CapacityAlertStatus.OPEN) notifyAdmins(alert);
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
        clearSuppression(alert);
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

    private CapacityAlertLevel determineLevel(long current, long threshold, Long projectedDays,
                                              CapacityPolicyService.EffectivePolicy policy) {
        if (!policy.enabled() || threshold <= 0) return null;
        if (current >= threshold) return CapacityAlertLevel.CRITICAL;
        if (current * 100.0 / threshold >= policy.warningPercent()) return CapacityAlertLevel.WARNING;
        if (projectedDays != null && projectedDays <= policy.watchDays()) return CapacityAlertLevel.WATCH;
        return null;
    }

    private void applySuppression(CapacityAlert alert, CapacityMaintenanceWindow maintenance) {
        alert.setNotificationSuppressed(true);
        alert.setSuppressedNotificationCount(value(alert.getSuppressedNotificationCount()) + 1);
        alert.setSuppressedByWindowId(maintenance.getId());
        String reason = maintenance.getName() + "：" + maintenance.getReason();
        alert.setSuppressedReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        alert.setLastSuppressedAt(LocalDateTime.now());
    }

    private void clearSuppression(CapacityAlert alert) {
        alert.setNotificationSuppressed(false);
        alert.setSuppressedByWindowId(null);
        alert.setSuppressedReason(null);
    }

    private void auditSuppressed(CapacityAlert alert, CapacityMaintenanceWindow maintenance) {
        auditLogService.record(alert.getTenantId(), null, "CAPACITY_ALERT", alert.getId(),
                "CAPACITY_ALERT_NOTIFICATION_SUPPRESSED", null, maintenance.getId(),
                "容量维护窗口内抑制通知：" + maintenance.getName());
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

    private List<AlertMetric> metrics(CapacityPolicyService.EffectivePolicy policy) {
        return List.of(
                new AlertMetric("activeTickets", "活跃工单", policy.activeTicketsThreshold(), CapacitySnapshot::getActiveTickets),
                new AlertMetric("overdueTickets", "逾期工单", policy.overdueTicketsThreshold(), CapacitySnapshot::getOverdueTickets),
                new AlertMetric("activeIncidents", "活跃事件", policy.activeIncidentsThreshold(), CapacitySnapshot::getActiveIncidents),
                new AlertMetric("firingAlerts", "未恢复告警", policy.firingAlertsThreshold(), CapacitySnapshot::getFiringAlerts),
                new AlertMetric("asyncBacklog", "异步消息积压", policy.asyncBacklogThreshold(), CapacitySnapshot::getAsyncBacklog),
                new AlertMetric("attachmentBacklog", "附件扫描积压", policy.attachmentBacklogThreshold(), CapacitySnapshot::getAttachmentBacklog),
                new AlertMetric("attachmentBytes", "附件存储", policy.attachmentBytesThreshold(), CapacitySnapshot::getAttachmentBytes),
                new AlertMetric("databaseBytes", "数据库体积", policy.databaseBytesThreshold(), CapacitySnapshot::getDatabaseBytes));
    }

    private int rank(CapacityAlertLevel level) {
        return switch (level) { case WATCH -> 1; case WARNING -> 2; case CRITICAL -> 3; };
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(long part, long total) {
        return total == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : decimal(part * 100.0 / total);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
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
    public record AlertHistoryReport(int days, long totalAlerts, long resolvedAlerts, long suppressedAlerts,
                                     BigDecimal resolutionRate, BigDecimal meanResolutionHours,
                                     List<DailyHistory> daily, List<MetricHistory> metrics) { }
    public record DailyHistory(LocalDate date, long created, long critical, long resolved, long suppressed) { }
    public record MetricHistory(String key, String label, long total, long critical, long resolved,
                                long suppressed) { }

    private static final class MutableDaily {
        private final LocalDate date;
        private long created;
        private long critical;
        private long resolved;
        private long suppressed;

        private MutableDaily(LocalDate date) { this.date = date; }
        private void add(CapacityAlert alert) {
            created++;
            if (alert.getLevel() == CapacityAlertLevel.CRITICAL) critical++;
            if (alert.getStatus() == CapacityAlertStatus.RESOLVED) resolved++;
            if (alert.getSuppressedNotificationCount() != null && alert.getSuppressedNotificationCount() > 0) suppressed++;
        }
        private DailyHistory view() { return new DailyHistory(date, created, critical, resolved, suppressed); }
    }

    private static final class MutableMetric {
        private final String key;
        private final String label;
        private long total;
        private long critical;
        private long resolved;
        private long suppressed;

        private MutableMetric(String key, String label) { this.key = key; this.label = label; }
        private void add(CapacityAlert alert) {
            total++;
            if (alert.getLevel() == CapacityAlertLevel.CRITICAL) critical++;
            if (alert.getStatus() == CapacityAlertStatus.RESOLVED) resolved++;
            if (alert.getSuppressedNotificationCount() != null && alert.getSuppressedNotificationCount() > 0) suppressed++;
        }
        private MetricHistory view() { return new MetricHistory(key, label, total, critical, resolved, suppressed); }
    }
}
