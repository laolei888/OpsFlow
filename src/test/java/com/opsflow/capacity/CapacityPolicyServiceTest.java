package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapacityPolicyServiceTest {
    @Mock private CapacityPolicyMapper policyMapper;
    @Mock private AuditLogService auditLogService;

    @Test
    void returnsGlobalDefaultsUntilTenantOverridesThem() {
        CapacityProperties properties = new CapacityProperties();
        properties.getThresholds().setActiveTickets(321);
        CapacityPolicyService service = new CapacityPolicyService(policyMapper, properties, auditLogService);
        when(policyMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        CapacityPolicyService.EffectivePolicy policy = service.effective(3L);

        assertThat(policy.activeTicketsThreshold()).isEqualTo(321);
        assertThat(policy.warningPercent()).isEqualTo(80);
        assertThat(policy.inheritedDefaults()).isTrue();
    }

    @Test
    void persistsValidatedTenantPolicyAndKeepsZeroAsDisabledMetric() {
        CapacityPolicyService service = new CapacityPolicyService(policyMapper, new CapacityProperties(), auditLogService);
        when(policyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        CapacityPolicyService.PolicyCommand command = new CapacityPolicyService.PolicyCommand(
                800L, 80L, 10L, 40L, 500L, 200L, 0L, 8_000_000_000L, 75, 21, true);

        CapacityPolicyService.EffectivePolicy result = service.save(command, 3L, 7L);

        ArgumentCaptor<CapacityPolicy> captor = ArgumentCaptor.forClass(CapacityPolicy.class);
        verify(policyMapper).insert(captor.capture());
        assertThat(captor.getValue().getAttachmentBytesThreshold()).isZero();
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(7L);
        assertThat(result.warningPercent()).isEqualTo(75);
        assertThat(result.inheritedDefaults()).isFalse();
    }

    @Test
    void rejectsUnsafeWarningWaterline() {
        CapacityPolicyService service = new CapacityPolicyService(policyMapper, new CapacityProperties(), auditLogService);
        CapacityPolicyService.PolicyCommand command = new CapacityPolicyService.PolicyCommand(
                800L, 80L, 10L, 40L, 500L, 200L, 1000L, 2000L, 30, 21, true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.save(command, 3L, 7L));

        assertThat(exception.getMessage()).contains("50% 到 99%");
        verify(policyMapper, never()).insert(any(CapacityPolicy.class));
    }
}
