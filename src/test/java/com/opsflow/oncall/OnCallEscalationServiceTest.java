package com.opsflow.oncall;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentSeverity;
import com.opsflow.messaging.AsyncNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnCallEscalationServiceTest {
    @Mock private OnCallEscalationPolicyMapper policyMapper;
    @Mock private OnCallEscalationStepMapper stepMapper;
    @Mock private OnCallEscalationEventMapper eventMapper;
    @Mock private OnCallEscalationDeliveryMapper deliveryMapper;
    @Mock private OnCallRecipientResolver recipientResolver;
    @Mock private AuthService authService;
    @Mock private AsyncNotificationPublisher notificationPublisher;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private OnCallEscalationService service;

    @Test
    void createsEscalationEventForMatchingSevOneIncident() {
        OnCallEscalationPolicy policy = policy(21L);
        when(policyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(policy));
        when(stepMapper.selectList(any(Wrapper.class))).thenReturn(List.of(step(1, 0)));
        when(eventMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(eventMapper.insert(any(OnCallEscalationEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, OnCallEscalationEvent.class).setId(71L);
            return 1;
        });

        service.triggerIncident(incident());

        ArgumentCaptor<OnCallEscalationEvent> captor = ArgumentCaptor.forClass(OnCallEscalationEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(OnCallEscalationEventStatus.ACTIVE, captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getCurrentStep());
        assertNotNull(captor.getValue().getNextEscalateAt());
        verify(auditLogService).record(eq(3L), eq(7L), eq("ONCALL_ESCALATION"), eq(71L),
                eq("ONCALL_ESCALATION_TRIGGERED"), eq(null), any(), contains("触发"));
    }

    @Test
    void dispatchesFirstStepToCurrentOnCallRecipientAndExhaustsPolicy() {
        OnCallEscalationEvent event = new OnCallEscalationEvent();
        event.setId(71L);
        event.setTenantId(3L);
        event.setIncidentId(61L);
        event.setPolicyId(21L);
        event.setIncidentNo("INC-TEST61");
        event.setIncidentTitle("订单服务大面积超时");
        event.setSeverity(IncidentSeverity.SEV1);
        event.setStatus(OnCallEscalationEventStatus.ACTIVE);
        event.setCurrentStep(0);
        event.setNextEscalateAt(LocalDateTime.now().minusMinutes(1));
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(event));
        when(eventMapper.selectForUpdate(71L)).thenReturn(event);
        when(stepMapper.selectList(any(Wrapper.class))).thenReturn(List.of(step(1, 0)));
        when(recipientResolver.activeRecipients(eq(3L), eq(11L), any())).thenReturn(Set.of(8L));
        when(notificationPublisher.enqueue(eq(3L), eq(8L), eq(null), eq("ONCALL_ESCALATION"),
                contains("L1"), contains("订单服务"), eq("ONCALL_ESCALATION:71:1:8"),
                eq("ONCALL_ESCALATION"), eq(71L))).thenReturn(true);

        service.dispatchDueEvents();

        assertEquals(1, event.getCurrentStep());
        assertEquals(OnCallEscalationEventStatus.EXHAUSTED, event.getStatus());
        verify(deliveryMapper).insert(any(OnCallEscalationDelivery.class));
        verify(auditLogService).record(eq(3L), eq(null), eq("ONCALL_ESCALATION"), eq(71L),
                eq("ONCALL_ESCALATION_DISPATCHED"), any(), any(), contains("派发"));
    }

    @Test
    void resolvingIncidentStopsActiveEscalations() {
        OnCallEscalationEvent event = new OnCallEscalationEvent();
        event.setId(71L);
        event.setTenantId(3L);
        event.setIncidentId(61L);
        event.setStatus(OnCallEscalationEventStatus.ACTIVE);
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(event));

        service.resolveIncident(incident(), 7L, false);

        assertEquals(OnCallEscalationEventStatus.RESOLVED, event.getStatus());
        assertNotNull(event.getResolvedAt());
        verify(eventMapper).updateById(event);
    }

    private OnCallEscalationPolicy policy(Long id) {
        OnCallEscalationPolicy policy = new OnCallEscalationPolicy();
        policy.setId(id);
        policy.setTenantId(3L);
        policy.setName("生产 SEV2 值班升级");
        policy.setEnabled(true);
        policy.setMinimumSeverity(IncidentSeverity.SEV2);
        return policy;
    }

    private OnCallEscalationStep step(int no, int delay) {
        OnCallEscalationStep step = new OnCallEscalationStep();
        step.setId((long) no);
        step.setTenantId(3L);
        step.setPolicyId(21L);
        step.setStepNo(no);
        step.setDelayMinutes(delay);
        step.setTargetType(OnCallEscalationTargetType.ON_CALL);
        step.setScheduleId(11L);
        return step;
    }

    private Incident incident() {
        Incident incident = new Incident();
        incident.setId(61L);
        incident.setTenantId(3L);
        incident.setIncidentNo("INC-TEST61");
        incident.setTitle("订单服务大面积超时");
        incident.setSeverity(IncidentSeverity.SEV1);
        incident.setDeclaredBy(7L);
        return incident;
    }
}
