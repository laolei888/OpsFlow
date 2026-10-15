package com.opsflow.alert;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import tools.jackson.databind.ObjectMapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.cmdb.CmdbAssetService;
import com.opsflow.common.BusinessException;
import com.opsflow.oncall.OnCallScheduleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertConfigServiceTest {
    @Mock private AlertSourceMapper sourceMapper;
    @Mock private AlertRuleMapper ruleMapper;
    @Mock private AlertSilenceMapper silenceMapper;
    @Mock private OnCallScheduleMapper scheduleMapper;
    @Mock private CmdbAssetService cmdbAssetService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private AlertConfigService service;

    @Test
    void createsSourceWithOneTimeHashedCredential() {
        when(sourceMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode(any(String.class))).thenReturn("bcrypt-hash");
        when(sourceMapper.insert(any(AlertSource.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AlertSource.class).setId(11L);
            return 1;
        });

        AlertConfigService.SourceCredential result = service.createSource(
                new AlertConfigService.SourceCommand("Prometheus 生产集群", "生产告警接入", null, true),
                7L, 3L);

        assertEquals(11L, result.source().getId());
        assertEquals("bcrypt-hash", result.source().getApiKeyHash());
        assertTrue(result.ingestKey().startsWith("of_alert_"));
        assertNotNull(result.source().getSourceKey());
        verify(passwordEncoder).encode(eq(result.ingestKey()));
        verify(auditLogService).record(eq(3L), eq(7L), eq("ALERT_SOURCE"), eq(11L),
                eq("ALERT_SOURCE_CREATED"), eq(null), eq(result.source()), contains("创建"));
    }

    @Test
    void neverSerializesStoredCredentialHash() throws Exception {
        AlertSource source = new AlertSource();
        source.setId(11L);
        source.setName("Prometheus 生产集群");
        source.setSourceKey("src_prod");
        source.setApiKeyHash("bcrypt-secret-hash");

        String json = new ObjectMapper().writeValueAsString(
                new AlertConfigService.SourceCredential(source, "one-time-plain-secret"));

        assertTrue(json.contains("one-time-plain-secret"));
        assertFalse(json.contains("bcrypt-secret-hash"));
        assertFalse(json.contains("apiKeyHash"));
    }

    @Test
    void rejectsUnscopedSilenceWindow() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 28, 20, 0);

        assertThrows(BusinessException.class, () -> service.createSilence(
                new AlertConfigService.SilenceCommand("全局静默", null, null, null,
                        start, start.plusHours(2), "维护窗口", true), 7L, 3L));

        verify(silenceMapper, never()).insert(any(AlertSilence.class));
    }

    @Test
    void matchesFirstEnabledRuleBySeverityAndSource() {
        AlertRule disabled = rule(1L, 10, AlertSeverity.CRITICAL, false, null);
        AlertRule sourceSpecific = rule(2L, 20, AlertSeverity.HIGH, true, 31L);
        AlertRule fallback = rule(3L, 30, AlertSeverity.MEDIUM, true, null);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(disabled, sourceSpecific, fallback));

        AlertRule matched = service.matchRule(3L, 31L, AlertSeverity.CRITICAL);

        assertEquals(2L, matched.getId());
    }

    private AlertRule rule(Long id, int order, AlertSeverity severity, boolean enabled, Long sourceId) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setTenantId(3L);
        rule.setName("规则 " + id);
        rule.setSortOrder(order);
        rule.setMinimumSeverity(severity);
        rule.setAction(AlertRuleAction.NOTIFY_ONCALL);
        rule.setEnabled(enabled);
        rule.setSourceId(sourceId);
        return rule;
    }
}
