package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.release.ReleasePlan;
import com.opsflow.release.ReleasePlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapacityPlanningServiceTest {
    @Mock private CapacitySnapshotMapper snapshotMapper;
    @Mock private CapacitySnapshotCollector collector;
    @Mock private ReleasePlanMapper releasePlanMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private CapacityAlertService capacityAlertService;

    private CapacityPlanningService service;

    @BeforeEach
    void setUp() {
        CapacityProperties properties = new CapacityProperties();
        properties.setAppVersion("test-1.0.0");
        service = new CapacityPlanningService(snapshotMapper, collector, releasePlanMapper, auditLogService,
                properties, capacityAlertService);
    }

    @Test
    void capturesManualSnapshotAndWritesAuditTrail() {
        when(collector.collect(3L)).thenReturn(measurement());
        doAnswer(invocation -> {
            invocation.<CapacitySnapshot>getArgument(0).setId(91L);
            return 1;
        }).when(snapshotMapper).insert(any(CapacitySnapshot.class));

        CapacitySnapshot result = service.captureManual(
                new CapacityPlanningService.CaptureCommand("扩容前", "v2.3.0", null), 3L, 9L);

        assertThat(result.getId()).isEqualTo(91L);
        assertThat(result.getCaptureType()).isEqualTo(CapacityCaptureType.MANUAL);
        assertThat(result.getActiveTickets()).isEqualTo(25L);
        assertThat(result.getDatabaseBytes()).isEqualTo(8_000L);
        verify(capacityAlertService).evaluate(result);
        verify(auditLogService).record(eq(3L), eq(9L), eq("CAPACITY_SNAPSHOT"), eq(91L),
                eq("CAPACITY_SNAPSHOT_CREATED"), eq(null), eq(result), any(String.class));
    }

    @Test
    void linksReleaseBaselineAndUsesReleaseNumberAsDefaults() {
        ReleasePlan release = new ReleasePlan();
        release.setId(44L);
        release.setTenantId(3L);
        release.setReleaseNo("REL-2026-0044");
        when(releasePlanMapper.selectOne(any(Wrapper.class))).thenReturn(release);
        when(collector.collect(3L)).thenReturn(measurement());

        CapacitySnapshot result = service.captureManual(
                new CapacityPlanningService.CaptureCommand(null, null, 44L), 3L, 9L);

        assertThat(result.getCaptureType()).isEqualTo(CapacityCaptureType.RELEASE_BASELINE);
        assertThat(result.getReleaseId()).isEqualTo(44L);
        assertThat(result.getAppVersion()).isEqualTo("REL-2026-0044");
        assertThat(result.getLabel()).contains("发布基线");
    }

    @Test
    void reportCalculatesGrowthThresholdAndForecast() {
        LocalDateTime now = LocalDateTime.now();
        CapacitySnapshot first = snapshot(1L, now.minusDays(10), 1_000, 100, 30, 20);
        CapacitySnapshot latest = snapshot(2L, now, 4_000, 450, 80, 60);
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, latest));

        CapacityPlanningService.CapacityReport report = service.report(3L, 30);

        CapacityPlanningService.MetricTrend active = report.trends().stream()
                .filter(metric -> metric.key().equals("activeTickets"))
                .findFirst().orElseThrow();
        assertThat(report.latest()).isSameAs(latest);
        assertThat(active.delta()).isEqualTo(3_000L);
        assertThat(active.utilizationPercent()).isEqualTo(80.0);
        assertThat(active.daysToLimit()).isEqualTo(4L);
        assertThat(active.level()).isEqualTo(CapacityPlanningService.CapacityLevel.WARNING);
    }

    @Test
    void comparisonClassifiesImprovementAndRejectsSameSnapshot() {
        LocalDateTime now = LocalDateTime.now();
        CapacitySnapshot baseline = snapshot(1L, now.minusDays(1), 100, 20, 15, 40);
        CapacitySnapshot current = snapshot(2L, now, 90, 10, 25, 20);
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(baseline, current);

        CapacityPlanningService.CapacityComparison comparison = service.compare(1L, 2L, 3L);

        assertThat(comparison.metrics()).anyMatch(metric -> metric.key().equals("overdueTickets")
                && metric.impact() == CapacityPlanningService.MetricImpact.IMPROVED);
        assertThat(comparison.metrics()).anyMatch(metric -> metric.key().equals("ticketsResolved24h")
                && metric.impact() == CapacityPlanningService.MetricImpact.IMPROVED);
        assertThrows(BusinessException.class, () -> service.compare(2L, 2L, 3L));
    }

    private CapacitySnapshotCollector.CapacityMeasurement measurement() {
        return new CapacitySnapshotCollector.CapacityMeasurement(
                8, 120, 25, 4, 12, 10, 30, 18, 2, 5, 3, 1,
                4_000, 8_000, 2_000, 16_000);
    }

    private CapacitySnapshot snapshot(Long id, LocalDateTime capturedAt, long activeTickets,
                                      long overdueTickets, long resolved24h, long asyncBacklog) {
        CapacitySnapshot snapshot = new CapacitySnapshot();
        snapshot.setId(id);
        snapshot.setTenantId(3L);
        snapshot.setCapturedAt(capturedAt);
        snapshot.setCaptureType(CapacityCaptureType.SCHEDULED);
        snapshot.setLabel("snapshot-" + id);
        snapshot.setAppVersion("v" + id);
        snapshot.setActiveUsers(8L);
        snapshot.setTotalTickets(activeTickets + 100);
        snapshot.setActiveTickets(activeTickets);
        snapshot.setOverdueTickets(overdueTickets);
        snapshot.setTicketsCreated24h(30L);
        snapshot.setTicketsResolved24h(resolved24h);
        snapshot.setPublishedArticles(12L);
        snapshot.setActiveAssets(20L);
        snapshot.setActiveIncidents(2L);
        snapshot.setFiringAlerts(5L);
        snapshot.setAsyncBacklog(asyncBacklog);
        snapshot.setAttachmentBacklog(2L);
        snapshot.setAttachmentBytes(2_000L);
        snapshot.setDatabaseBytes(5_000L);
        snapshot.setHeapUsedBytes(1_000L);
        snapshot.setHeapMaxBytes(10_000L);
        return snapshot;
    }
}
