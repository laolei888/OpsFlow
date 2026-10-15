package com.opsflow.alert;

import tools.jackson.databind.ObjectMapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.cmdb.CmdbAssetService;
import com.opsflow.incident.IncidentService;
import com.opsflow.notification.NotificationService;
import com.opsflow.oncall.OnCallRecipientResolver;
import com.opsflow.runbook.RunbookService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {
    @Mock private MonitoringAlertMapper alertMapper;
    @Mock private AlertEventMapper eventMapper;
    @Mock private AlertRuleMapper ruleMapper;
    @Mock private AlertSourceMapper sourceMapper;
    @Mock private AlertConfigService configService;
    @Mock private CmdbAssetService cmdbAssetService;
    @Mock private TicketService ticketService;
    @Mock private IncidentService incidentService;
    @Mock private NotificationService notificationService;
    @Mock private OnCallRecipientResolver onCallRecipientResolver;
    @Mock private RunbookService runbookService;
    @Mock private AuthService authService;
    @Mock private AuditLogService auditLogService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private AlertService service;

    @Test
    void ingestsNewAlertAndNotifiesCurrentOnCall() {
        AlertSource source = source();
        AlertRule rule = rule(AlertRuleAction.NOTIFY_ONCALL);
        when(configService.authenticate("src_prod", "secret")).thenReturn(source);
        when(configService.activeSilence(eq(3L), eq(11L), eq(null), eq("api:error-rate"), any(LocalDateTime.class)))
                .thenReturn(null);
        when(alertMapper.selectByFingerprintForUpdate(3L, 11L, "api:error-rate")).thenReturn(null);
        when(alertMapper.insert(any(MonitoringAlert.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, MonitoringAlert.class).setId(91L);
            return 1;
        });
        when(configService.matchRule(3L, 11L, AlertSeverity.CRITICAL)).thenReturn(rule);
        when(onCallRecipientResolver.activePrimaryRecipients(eq(3L), any(LocalDateTime.class))).thenReturn(Set.of(8L));

        AlertService.IngestResult result = service.ingest("src_prod", "secret",
                command(AlertSeverity.CRITICAL, AlertSignalState.FIRING));

        assertTrue(result.created());
        assertFalse(result.aggregated());
        assertEquals(AlertStatus.FIRING, result.alert().getStatus());
        assertEquals(AlertRuleAction.NOTIFY_ONCALL, result.appliedAction());
        verify(notificationService).notifyIfAbsent(eq(3L), eq(8L), eq("MONITORING_ALERT"),
                contains("CRITICAL"), contains("订单 API"), eq("MONITORING_ALERT:91:1:8"));
        verify(configService).markReceived(eq(source), any(LocalDateTime.class));
    }

    @Test
    void aggregatesRepeatedFingerprintWithoutRepeatingAutomation() {
        AlertSource source = source();
        MonitoringAlert existing = alert(AlertStatus.FIRING, AlertSeverity.HIGH, 4);
        when(configService.authenticate("src_prod", "secret")).thenReturn(source);
        when(configService.activeSilence(eq(3L), eq(11L), eq(null), eq("api:error-rate"), any(LocalDateTime.class)))
                .thenReturn(null);
        when(alertMapper.selectByFingerprintForUpdate(3L, 11L, "api:error-rate")).thenReturn(existing);

        AlertService.IngestResult result = service.ingest("src_prod", "secret",
                command(AlertSeverity.HIGH, AlertSignalState.FIRING));

        assertTrue(result.aggregated());
        assertEquals(5, result.alert().getOccurrenceCount());
        verify(configService, never()).matchRule(any(), any(), any());
        verify(notificationService, never()).notifyIfAbsent(any(), any(), any(), any(), any(), any());
        verify(eventMapper).insert(any(AlertEvent.class));
    }

    @Test
    void suppressesAlertThatMatchesMaintenanceWindow() {
        AlertSource source = source();
        AlertSilence silence = new AlertSilence();
        silence.setId(21L);
        silence.setName("订单系统发布窗口");
        silence.setReason("计划内版本发布");
        when(configService.authenticate("src_prod", "secret")).thenReturn(source);
        when(configService.activeSilence(eq(3L), eq(11L), eq(null), eq("api:error-rate"), any(LocalDateTime.class)))
                .thenReturn(silence);
        when(alertMapper.selectByFingerprintForUpdate(3L, 11L, "api:error-rate")).thenReturn(null);
        when(alertMapper.insert(any(MonitoringAlert.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, MonitoringAlert.class).setId(92L);
            return 1;
        });

        AlertService.IngestResult result = service.ingest("src_prod", "secret",
                command(AlertSeverity.HIGH, AlertSignalState.FIRING));

        assertEquals(AlertStatus.SUPPRESSED, result.alert().getStatus());
        verify(configService, never()).matchRule(any(), any(), any());
        verify(auditLogService).record(eq(3L), eq(7L), eq("MONITORING_ALERT"), eq(92L),
                eq("ALERT_SUPPRESSED"), eq(null), eq(result.alert()), contains("静默"));
    }

    @Test
    void createsTicketWhenAutomationRuleRequiresIt() {
        AlertSource source = source();
        AlertRule rule = rule(AlertRuleAction.CREATE_TICKET);
        when(configService.authenticate("src_prod", "secret")).thenReturn(source);
        when(configService.activeSilence(eq(3L), eq(11L), eq(null), eq("api:error-rate"), any(LocalDateTime.class)))
                .thenReturn(null);
        when(alertMapper.selectByFingerprintForUpdate(3L, 11L, "api:error-rate")).thenReturn(null);
        when(alertMapper.insert(any(MonitoringAlert.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, MonitoringAlert.class).setId(93L);
            return 1;
        });
        when(configService.matchRule(3L, 11L, AlertSeverity.HIGH)).thenReturn(rule);
        Ticket ticket = new Ticket();
        ticket.setId(55L);
        ticket.setTicketNo("OPS-20260928-0001");
        when(ticketService.create(any(Ticket.class), eq(7L), eq(3L), eq("monitor-alert-ticket-93-1")))
                .thenReturn(ticket);
        when(onCallRecipientResolver.activePrimaryRecipients(eq(3L), any(LocalDateTime.class))).thenReturn(Set.of(8L));

        AlertService.IngestResult result = service.ingest("src_prod", "secret",
                command(AlertSeverity.HIGH, AlertSignalState.FIRING));

        assertEquals(55L, result.alert().getTicketId());
        verify(ticketService).create(any(Ticket.class), eq(7L), eq(3L), eq("monitor-alert-ticket-93-1"));
        verify(ticketService).create(org.mockito.ArgumentMatchers.argThat(created ->
                        created.getPriority() == TicketPriority.HIGH && "监控告警".equals(created.getCategory())),
                eq(7L), eq(3L), eq("monitor-alert-ticket-93-1"));
    }

    private AlertSource source() {
        AlertSource source = new AlertSource();
        source.setId(11L);
        source.setTenantId(3L);
        source.setName("Prometheus 生产集群");
        source.setSourceKey("src_prod");
        source.setCreatedBy(7L);
        source.setEnabled(true);
        return source;
    }

    private AlertRule rule(AlertRuleAction action) {
        AlertRule rule = new AlertRule();
        rule.setId(31L);
        rule.setTenantId(3L);
        rule.setName("生产高优告警");
        rule.setMinimumSeverity(AlertSeverity.HIGH);
        rule.setAction(action);
        rule.setEnabled(true);
        rule.setSortOrder(10);
        return rule;
    }

    private MonitoringAlert alert(AlertStatus status, AlertSeverity severity, int occurrences) {
        MonitoringAlert alert = new MonitoringAlert();
        alert.setId(91L);
        alert.setAlertNo("ALT-ABC12345");
        alert.setTenantId(3L);
        alert.setSourceId(11L);
        alert.setFingerprint("api:error-rate");
        alert.setTitle("订单 API 5xx 错误率过高");
        alert.setSeverity(severity);
        alert.setStatus(status);
        alert.setOccurrenceCount(occurrences);
        alert.setGeneration(1);
        alert.setFirstSeenAt(LocalDateTime.now().minusMinutes(5));
        return alert;
    }

    private AlertService.IngestCommand command(AlertSeverity severity, AlertSignalState state) {
        return new AlertService.IngestCommand("api:error-rate", "prom-9001",
                "订单 API 5xx 错误率过高", "过去五分钟错误率超过 10%", severity, state, null,
                Map.of("service", "order-api", "env", "prod"));
    }
}
