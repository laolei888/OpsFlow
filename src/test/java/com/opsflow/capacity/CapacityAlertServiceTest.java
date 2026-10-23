package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.common.BusinessException;
import com.opsflow.messaging.AsyncNotificationPublisher;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapacityAlertServiceTest {
    @Mock private CapacityAlertMapper alertMapper;
    @Mock private CapacitySnapshotMapper snapshotMapper;
    @Mock private AuthService authService;
    @Mock private AsyncNotificationPublisher notificationPublisher;
    @Mock private AuditLogService auditLogService;

    private CapacityAlertService service;
    private CapacityProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CapacityProperties();
        properties.getThresholds().setActiveTickets(100);
        service = new CapacityAlertService(alertMapper, snapshotMapper, properties, authService,
                notificationPublisher, auditLogService);
    }

    @Test
    void createsCriticalAlertAndNotifiesTenantAdministrators() {
        CapacitySnapshot snapshot = snapshot(11L, 100);
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(alertMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(authService.staffMembers(3L)).thenReturn(List.of(
                new StaffSummary(7L, "admin", "平台管理员", List.of("ADMIN")),
                new StaffSummary(8L, "agent", "一线工程师", List.of("AGENT"))));
        doAnswer(invocation -> {
            invocation.<CapacityAlert>getArgument(0).setId(51L);
            return 1;
        }).when(alertMapper).insert(any(CapacityAlert.class));

        service.evaluate(snapshot);

        ArgumentCaptor<CapacityAlert> alertCaptor = ArgumentCaptor.forClass(CapacityAlert.class);
        verify(alertMapper).insert(alertCaptor.capture());
        CapacityAlert alert = alertCaptor.getValue();
        assertThat(alert.getId()).isEqualTo(51L);
        assertThat(alert.getMetricKey()).isEqualTo("activeTickets");
        assertThat(alert.getLevel()).isEqualTo(CapacityAlertLevel.CRITICAL);
        assertThat(alert.getStatus()).isEqualTo(CapacityAlertStatus.OPEN);
        assertThat(alert.getUtilizationPercent()).isEqualByComparingTo("100.00");
        verify(notificationPublisher).enqueue(eq(3L), eq(7L), eq(null), eq("CAPACITY_THRESHOLD"),
                eq("容量阈值已触顶"), any(String.class), eq("CAPACITY_ALERT:51:CRITICAL"),
                eq("CAPACITY_ALERT"), eq(51L));
        verify(notificationPublisher, never()).enqueue(eq(3L), eq(8L), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void escalatesAcknowledgedAlertAndReopensIt() {
        CapacityAlert existing = alert(CapacityAlertStatus.ACKNOWLEDGED, CapacityAlertLevel.WATCH);
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(alertMapper.selectOne(any(Wrapper.class))).thenReturn(existing, null, null, null, null, null, null, null);
        when(authService.staffMembers(3L)).thenReturn(List.of(
                new StaffSummary(7L, "admin", "平台管理员", List.of("ADMIN"))));

        service.evaluate(snapshot(12L, 100));

        assertThat(existing.getLevel()).isEqualTo(CapacityAlertLevel.CRITICAL);
        assertThat(existing.getStatus()).isEqualTo(CapacityAlertStatus.OPEN);
        assertThat(existing.getAcknowledgedBy()).isNull();
        assertThat(existing.getOccurrenceCount()).isEqualTo(2);
        verify(auditLogService).record(eq(3L), eq(null), eq("CAPACITY_ALERT"), eq(51L),
                eq("CAPACITY_ALERT_ESCALATED"), eq(CapacityAlertLevel.WATCH),
                eq(CapacityAlertLevel.CRITICAL), any(String.class));
    }

    @Test
    void automaticallyResolvesAlertAfterMetricReturnsToSafeRange() {
        CapacityAlert existing = alert(CapacityAlertStatus.OPEN, CapacityAlertLevel.WARNING);
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(alertMapper.selectOne(any(Wrapper.class))).thenReturn(existing, null, null, null, null, null, null, null);

        service.evaluate(snapshot(13L, 20));

        assertThat(existing.getStatus()).isEqualTo(CapacityAlertStatus.RESOLVED);
        assertThat(existing.getCurrentValue()).isEqualTo(20L);
        assertThat(existing.getUtilizationPercent()).isEqualByComparingTo("20.00");
        assertThat(existing.getActiveKey()).isNull();
        assertThat(existing.getResolutionNote()).isEqualTo("指标已恢复到安全范围");
        verify(auditLogService).record(eq(3L), eq(null), eq("CAPACITY_ALERT"), eq(51L),
                eq("CAPACITY_ALERT_AUTO_RESOLVED"), eq(CapacityAlertStatus.OPEN),
                eq(CapacityAlertStatus.RESOLVED), any(String.class));
    }

    @Test
    void disabledThresholdResolvesActiveAlertWithoutDivisionByZero() {
        properties.getThresholds().setActiveTickets(0);
        CapacityAlert existing = alert(CapacityAlertStatus.OPEN, CapacityAlertLevel.WARNING);
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(alertMapper.selectOne(any(Wrapper.class))).thenReturn(existing, null, null, null, null, null, null, null);

        service.evaluate(snapshot(14L, 20));

        assertThat(existing.getStatus()).isEqualTo(CapacityAlertStatus.RESOLVED);
        assertThat(existing.getThresholdValue()).isZero();
        assertThat(existing.getUtilizationPercent()).isEqualByComparingTo("0.00");
    }

    @Test
    void acknowledgesOpenAlertWithNormalizedNote() {
        CapacityAlert alert = alert(CapacityAlertStatus.OPEN, CapacityAlertLevel.WARNING);
        when(alertMapper.selectOne(any(Wrapper.class))).thenReturn(alert);

        CapacityAlert result = service.acknowledge(51L, "  已安排扩容窗口  ", 3L, 7L);

        assertThat(result.getStatus()).isEqualTo(CapacityAlertStatus.ACKNOWLEDGED);
        assertThat(result.getAcknowledgedBy()).isEqualTo(7L);
        assertThat(result.getAcknowledgementNote()).isEqualTo("已安排扩容窗口");
        verify(alertMapper).updateById(alert);
    }

    @Test
    void requiresMeaningfulResolutionNote() {
        CapacityAlert alert = alert(CapacityAlertStatus.OPEN, CapacityAlertLevel.WARNING);
        when(alertMapper.selectOne(any(Wrapper.class))).thenReturn(alert);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolve(51L, "完成", 3L, 7L));

        assertThat(exception.getMessage()).contains("至少需要 5 个字符");
        verify(alertMapper, never()).updateById(any(CapacityAlert.class));
    }

    private CapacitySnapshot snapshot(Long id, long activeTickets) {
        CapacitySnapshot snapshot = new CapacitySnapshot();
        snapshot.setId(id);
        snapshot.setTenantId(3L);
        snapshot.setCapturedAt(LocalDateTime.now());
        snapshot.setActiveTickets(activeTickets);
        snapshot.setOverdueTickets(0L);
        snapshot.setActiveIncidents(0L);
        snapshot.setFiringAlerts(0L);
        snapshot.setAsyncBacklog(0L);
        snapshot.setAttachmentBacklog(0L);
        snapshot.setAttachmentBytes(0L);
        snapshot.setDatabaseBytes(0L);
        return snapshot;
    }

    private CapacityAlert alert(CapacityAlertStatus status, CapacityAlertLevel level) {
        CapacityAlert alert = new CapacityAlert();
        alert.setId(51L);
        alert.setTenantId(3L);
        alert.setAlertNo("CAP-TEST0001");
        alert.setMetricKey("activeTickets");
        alert.setMetricLabel("活跃工单");
        alert.setStatus(status);
        alert.setLevel(level);
        alert.setCurrentValue(80L);
        alert.setThresholdValue(100L);
        alert.setOccurrenceCount(1);
        alert.setAcknowledgedBy(status == CapacityAlertStatus.ACKNOWLEDGED ? 7L : null);
        alert.setAcknowledgedAt(status == CapacityAlertStatus.ACKNOWLEDGED ? LocalDateTime.now() : null);
        alert.setActiveKey("3:activeTickets");
        alert.setFirstDetectedAt(LocalDateTime.now().minusHours(1));
        alert.setLastDetectedAt(LocalDateTime.now().minusMinutes(5));
        return alert;
    }
}
