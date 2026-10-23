package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.release.ReleasePlan;
import com.opsflow.release.ReleasePlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToLongFunction;

@Service
@RequiredArgsConstructor
public class CapacityPlanningService {
    private static final int MAX_POINTS = 180;

    private final CapacitySnapshotMapper snapshotMapper;
    private final CapacitySnapshotCollector collector;
    private final ReleasePlanMapper releasePlanMapper;
    private final AuditLogService auditLogService;
    private final CapacityProperties properties;
    private final CapacityAlertService capacityAlertService;
    private final CapacityPolicyService policyService;

    @Transactional
    public CapacitySnapshot captureManual(CaptureCommand command, Long tenantId, Long actorId) {
        ReleasePlan release = null;
        if (command.releaseId() != null) {
            release = releasePlanMapper.selectOne(new LambdaQueryWrapper<ReleasePlan>()
                    .eq(ReleasePlan::getId, command.releaseId())
                    .eq(ReleasePlan::getTenantId, tenantId));
            if (release == null) throw new BusinessException("关联发布单不存在或不属于当前租户");
        }
        String label = normalize(command.label(), release == null ? "手工容量快照" : release.getReleaseNo() + " 发布基线", 120);
        String version = normalize(command.appVersion(), release == null ? properties.getAppVersion() : release.getReleaseNo(), 64);
        CapacitySnapshot snapshot = capture(tenantId, actorId,
                release == null ? CapacityCaptureType.MANUAL : CapacityCaptureType.RELEASE_BASELINE,
                label, version, release == null ? null : release.getId());
        auditLogService.record(tenantId, actorId, "CAPACITY_SNAPSHOT", snapshot.getId(),
                "CAPACITY_SNAPSHOT_CREATED", null, snapshot,
                "创建容量快照：" + snapshot.getLabel() + "（" + snapshot.getAppVersion() + "）");
        return snapshot;
    }

    @Transactional
    public CapacitySnapshot captureScheduled(Long tenantId) {
        CapacitySnapshot latest = latest(tenantId);
        LocalDateTime cutoff = LocalDateTime.now().minusNanos(properties.getCaptureIntervalMs() * 1_000_000L);
        if (latest != null && latest.getCapturedAt().isAfter(cutoff)) return latest;
        return capture(tenantId, null, CapacityCaptureType.SCHEDULED,
                "定时容量快照", properties.getAppVersion(), null);
    }

    public CapacityReport report(Long tenantId, Integer requestedDays) {
        int days = requestedDays == null ? 30 : Math.max(7, Math.min(requestedDays, 180));
        List<CapacitySnapshot> all = snapshotMapper.selectList(new LambdaQueryWrapper<CapacitySnapshot>()
                .eq(CapacitySnapshot::getTenantId, tenantId)
                .ge(CapacitySnapshot::getCapturedAt, LocalDateTime.now().minusDays(days))
                .orderByAsc(CapacitySnapshot::getCapturedAt)
                .orderByAsc(CapacitySnapshot::getId));
        CapacitySnapshot latest = all.isEmpty() ? null : all.get(all.size() - 1);
        CapacityPolicyService.EffectivePolicy policy = policyService.effective(tenantId);
        return new CapacityReport(days, all.size(), latest, sample(all), buildTrends(all, definitions(policy), policy));
    }

    public CapacityComparison compare(Long baselineId, Long currentId, Long tenantId) {
        if (baselineId == null || currentId == null || baselineId.equals(currentId)) {
            throw new BusinessException("请选择两个不同的容量快照进行比较");
        }
        CapacitySnapshot baseline = requiredSnapshot(baselineId, tenantId);
        CapacitySnapshot current = requiredSnapshot(currentId, tenantId);
        List<MetricDelta> metrics = definitions(policyService.effective(tenantId)).stream()
                .map(definition -> delta(definition, baseline, current))
                .toList();
        long improved = metrics.stream().filter(metric -> metric.impact() == MetricImpact.IMPROVED).count();
        long regressed = metrics.stream().filter(metric -> metric.impact() == MetricImpact.REGRESSED).count();
        return new CapacityComparison(baseline, current, metrics, improved, regressed);
    }

    @Transactional
    public int purgeExpired() {
        return snapshotMapper.delete(new LambdaQueryWrapper<CapacitySnapshot>()
                .lt(CapacitySnapshot::getCapturedAt, LocalDateTime.now().minusDays(properties.getRetentionDays()))
                .ne(CapacitySnapshot::getCaptureType, CapacityCaptureType.RELEASE_BASELINE));
    }

    private CapacitySnapshot capture(Long tenantId, Long actorId, CapacityCaptureType captureType,
                                     String label, String appVersion, Long releaseId) {
        CapacitySnapshotCollector.CapacityMeasurement measurement = collector.collect(tenantId);
        LocalDateTime now = LocalDateTime.now();
        CapacitySnapshot snapshot = new CapacitySnapshot();
        snapshot.setTenantId(tenantId);
        snapshot.setCapturedAt(now);
        snapshot.setCaptureType(captureType);
        snapshot.setLabel(label);
        snapshot.setAppVersion(appVersion);
        snapshot.setReleaseId(releaseId);
        snapshot.setActiveUsers(measurement.activeUsers());
        snapshot.setTotalTickets(measurement.totalTickets());
        snapshot.setActiveTickets(measurement.activeTickets());
        snapshot.setOverdueTickets(measurement.overdueTickets());
        snapshot.setTicketsCreated24h(measurement.ticketsCreated24h());
        snapshot.setTicketsResolved24h(measurement.ticketsResolved24h());
        snapshot.setPublishedArticles(measurement.publishedArticles());
        snapshot.setActiveAssets(measurement.activeAssets());
        snapshot.setActiveIncidents(measurement.activeIncidents());
        snapshot.setFiringAlerts(measurement.firingAlerts());
        snapshot.setAsyncBacklog(measurement.asyncBacklog());
        snapshot.setAttachmentBacklog(measurement.attachmentBacklog());
        snapshot.setAttachmentBytes(measurement.attachmentBytes());
        snapshot.setDatabaseBytes(measurement.databaseBytes());
        snapshot.setHeapUsedBytes(measurement.heapUsedBytes());
        snapshot.setHeapMaxBytes(measurement.heapMaxBytes());
        snapshot.setCreatedBy(actorId);
        snapshot.setCreatedAt(now);
        snapshotMapper.insert(snapshot);
        capacityAlertService.evaluate(snapshot);
        return snapshot;
    }

    private CapacitySnapshot latest(Long tenantId) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<CapacitySnapshot>()
                .eq(CapacitySnapshot::getTenantId, tenantId)
                .orderByDesc(CapacitySnapshot::getCapturedAt)
                .orderByDesc(CapacitySnapshot::getId)
                .last("LIMIT 1"));
    }

    private CapacitySnapshot requiredSnapshot(Long id, Long tenantId) {
        CapacitySnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<CapacitySnapshot>()
                .eq(CapacitySnapshot::getId, id)
                .eq(CapacitySnapshot::getTenantId, tenantId));
        if (snapshot == null) throw new BusinessException("容量快照不存在或不属于当前租户");
        return snapshot;
    }

    private List<MetricTrend> buildTrends(List<CapacitySnapshot> snapshots, List<MetricDefinition> definitions,
                                          CapacityPolicyService.EffectivePolicy policy) {
        if (snapshots.isEmpty()) return List.of();
        CapacitySnapshot first = snapshots.get(0);
        CapacitySnapshot last = snapshots.get(snapshots.size() - 1);
        double elapsedDays = Math.max(Duration.between(first.getCapturedAt(), last.getCapturedAt()).toMinutes() / 1440.0, 1.0 / 24.0);
        return definitions.stream().map(definition -> {
            long start = definition.reader().applyAsLong(first);
            long end = definition.reader().applyAsLong(last);
            long delta = end - start;
            double dailyGrowth = round(delta / elapsedDays);
            Double percent = percentage(start, delta);
            Double utilization = definition.threshold() == null || definition.threshold() <= 0
                    ? null : round(end * 100.0 / definition.threshold());
            Long daysToLimit = definition.threshold() == null || end >= definition.threshold() || dailyGrowth <= 0
                    ? null : Math.max(0, (long) Math.ceil((definition.threshold() - end) / dailyGrowth));
            CapacityLevel level = level(end, definition.threshold(), daysToLimit, policy);
            return new MetricTrend(definition.key(), definition.label(), definition.unit(), start, end,
                    delta, percent, dailyGrowth, definition.threshold(), utilization, daysToLimit, level);
        }).toList();
    }

    private MetricDelta delta(MetricDefinition definition, CapacitySnapshot baseline, CapacitySnapshot current) {
        long before = definition.reader().applyAsLong(baseline);
        long after = definition.reader().applyAsLong(current);
        long difference = after - before;
        MetricImpact impact;
        if (difference == 0) impact = MetricImpact.STABLE;
        else if (definition.direction() == Direction.NEUTRAL) impact = MetricImpact.NEUTRAL;
        else if ((difference > 0 && definition.direction() == Direction.HIGHER_IS_BETTER)
                || (difference < 0 && definition.direction() == Direction.LOWER_IS_BETTER)) impact = MetricImpact.IMPROVED;
        else impact = MetricImpact.REGRESSED;
        return new MetricDelta(definition.key(), definition.label(), definition.unit(), before, after,
                difference, percentage(before, difference), impact);
    }

    private List<CapacitySnapshot> sample(List<CapacitySnapshot> snapshots) {
        if (snapshots.size() <= MAX_POINTS) return snapshots;
        List<CapacitySnapshot> sampled = new ArrayList<>(MAX_POINTS);
        double step = (snapshots.size() - 1.0) / (MAX_POINTS - 1.0);
        for (int index = 0; index < MAX_POINTS; index++) {
            sampled.add(snapshots.get((int) Math.round(index * step)));
        }
        return Collections.unmodifiableList(sampled);
    }

    private List<MetricDefinition> definitions(CapacityPolicyService.EffectivePolicy policy) {
        return List.of(
                new MetricDefinition("activeTickets", "活跃工单", "张", CapacitySnapshot::getActiveTickets, policy.activeTicketsThreshold(), Direction.LOWER_IS_BETTER),
                new MetricDefinition("overdueTickets", "逾期工单", "张", CapacitySnapshot::getOverdueTickets, policy.overdueTicketsThreshold(), Direction.LOWER_IS_BETTER),
                new MetricDefinition("ticketsCreated24h", "24h 新增", "张", CapacitySnapshot::getTicketsCreated24h, null, Direction.NEUTRAL),
                new MetricDefinition("ticketsResolved24h", "24h 解决", "张", CapacitySnapshot::getTicketsResolved24h, null, Direction.HIGHER_IS_BETTER),
                new MetricDefinition("activeIncidents", "活跃事件", "起", CapacitySnapshot::getActiveIncidents, policy.activeIncidentsThreshold(), Direction.LOWER_IS_BETTER),
                new MetricDefinition("firingAlerts", "未恢复告警", "条", CapacitySnapshot::getFiringAlerts, policy.firingAlertsThreshold(), Direction.LOWER_IS_BETTER),
                new MetricDefinition("asyncBacklog", "异步消息积压", "条", CapacitySnapshot::getAsyncBacklog, policy.asyncBacklogThreshold(), Direction.LOWER_IS_BETTER),
                new MetricDefinition("attachmentBacklog", "附件扫描积压", "份", CapacitySnapshot::getAttachmentBacklog, policy.attachmentBacklogThreshold(), Direction.LOWER_IS_BETTER),
                new MetricDefinition("attachmentBytes", "附件存储", "bytes", CapacitySnapshot::getAttachmentBytes, policy.attachmentBytesThreshold(), Direction.NEUTRAL),
                new MetricDefinition("databaseBytes", "数据库体积", "bytes", CapacitySnapshot::getDatabaseBytes, policy.databaseBytesThreshold(), Direction.NEUTRAL),
                new MetricDefinition("heapUsedBytes", "JVM 堆使用", "bytes", CapacitySnapshot::getHeapUsedBytes, null, Direction.NEUTRAL),
                new MetricDefinition("activeUsers", "活跃成员", "人", CapacitySnapshot::getActiveUsers, null, Direction.NEUTRAL),
                new MetricDefinition("publishedArticles", "已发布知识", "篇", CapacitySnapshot::getPublishedArticles, null, Direction.HIGHER_IS_BETTER),
                new MetricDefinition("activeAssets", "在管资产", "项", CapacitySnapshot::getActiveAssets, null, Direction.NEUTRAL)
        );
    }

    private CapacityLevel level(long value, Long threshold, Long daysToLimit,
                                CapacityPolicyService.EffectivePolicy policy) {
        if (!policy.enabled() || threshold == null || threshold <= 0) return CapacityLevel.INFO;
        double ratio = value * 1.0 / threshold;
        if (ratio >= 1) return CapacityLevel.CRITICAL;
        if (ratio * 100 >= policy.warningPercent()) return CapacityLevel.WARNING;
        if (daysToLimit != null && daysToLimit <= policy.watchDays()) return CapacityLevel.WATCH;
        return CapacityLevel.HEALTHY;
    }

    private Double percentage(long baseline, long delta) {
        return baseline == 0 ? null : round(delta * 100.0 / baseline);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String normalize(String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record MetricDefinition(String key, String label, String unit,
                                    ToLongFunction<CapacitySnapshot> reader, Long threshold,
                                    Direction direction) { }

    private enum Direction { LOWER_IS_BETTER, HIGHER_IS_BETTER, NEUTRAL }
    public enum CapacityLevel { HEALTHY, WATCH, WARNING, CRITICAL, INFO }
    public enum MetricImpact { IMPROVED, REGRESSED, STABLE, NEUTRAL }

    public record CaptureCommand(String label, String appVersion, Long releaseId) { }
    public record CapacityReport(int days, int totalSnapshots, CapacitySnapshot latest,
                                 List<CapacitySnapshot> snapshots, List<MetricTrend> trends) { }
    public record MetricTrend(String key, String label, String unit, long startValue, long currentValue,
                              long delta, Double changePercent, double dailyGrowth, Long threshold,
                              Double utilizationPercent, Long daysToLimit, CapacityLevel level) { }
    public record CapacityComparison(CapacitySnapshot baseline, CapacitySnapshot current,
                                     List<MetricDelta> metrics, long improvedMetrics, long regressedMetrics) { }
    public record MetricDelta(String key, String label, String unit, long baselineValue, long currentValue,
                              long delta, Double changePercent, MetricImpact impact) { }
}
