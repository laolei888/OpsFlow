package com.opsflow.sla;

import com.opsflow.audit.AuditLogService;
import com.opsflow.ticket.TicketPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaPolicyServiceTest {
    @Mock
    private SlaPolicyMapper slaPolicyMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private SlaPolicyService slaPolicyService;

    @Test
    void usesSafeDefaultsWhenTenantHasNoCustomPolicy() {
        when(slaPolicyMapper.selectById(3L)).thenReturn(null);

        assertEquals(4, slaPolicyService.get(3L).urgentHours());
        assertEquals(72, slaPolicyService.durationFor(3L, TicketPriority.LOW).toHours());
    }

    @Test
    void updatesTenantPolicyAndRecordsAudit() {
        when(slaPolicyMapper.selectById(3L)).thenReturn(null);

        SlaPolicyView updated = slaPolicyService.update(3L, 2, 6, 18, 48, 9L);

        assertEquals(2, updated.urgentHours());
        assertEquals(48, updated.lowHours());
        verify(slaPolicyMapper).insert(any(SlaPolicyConfig.class));
        verify(auditLogService).record(eq(3L), eq(9L), eq("SLA_POLICY"), eq(3L),
                eq("SLA_POLICY_UPDATED"), any(), any(), eq("更新租户 SLA 策略"));
    }

    @Test
    void calculatesDueAtFromConfiguredHours() {
        SlaPolicyConfig config = new SlaPolicyConfig();
        config.setTenantId(3L);
        config.setUrgentHours(1);
        config.setHighHours(2);
        config.setMediumHours(3);
        config.setLowHours(4);
        when(slaPolicyMapper.selectById(3L)).thenReturn(config);
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 20, 10, 0);

        assertEquals(createdAt.plusHours(2), slaPolicyService.dueAt(3L, TicketPriority.HIGH, createdAt));
    }
}
