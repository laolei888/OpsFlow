package com.opsflow.oncall;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.incident.IncidentSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnCallServiceTest {
    @Mock private OnCallScheduleMapper scheduleMapper;
    @Mock private OnCallShiftMapper shiftMapper;
    @Mock private OnCallEscalationPolicyMapper policyMapper;
    @Mock private OnCallEscalationStepMapper stepMapper;
    @Mock private AuthService authService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private OnCallService service;

    @Test
    void createsPrimaryShiftForStaffMember() {
        when(scheduleMapper.selectOne(any(Wrapper.class))).thenReturn(schedule(11L));
        when(authService.isStaffMember(8L, 3L)).thenReturn(true);
        when(shiftMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(shiftMapper.insert(any(OnCallShift.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, OnCallShift.class).setId(31L);
            return 1;
        });

        OnCallShift result = service.createShift(11L, new OnCallService.ShiftCommand(8L,
                LocalDateTime.of(2026, 9, 28, 9, 0), LocalDateTime.of(2026, 9, 28, 17, 0),
                true, "交接关注订单接口错误率"), 7L, 3L);

        assertEquals(11L, result.getScheduleId());
        assertEquals(8L, result.getUserId());
        assertEquals(Boolean.TRUE, result.getPrimary());
        verify(auditLogService).record(eq(3L), eq(7L), eq("ONCALL_SHIFT"), eq(31L),
                eq("ONCALL_SHIFT_CREATED"), eq(null), eq(result), contains("创建"));
    }

    @Test
    void rejectsOverlappingPrimaryShift() {
        when(scheduleMapper.selectOne(any(Wrapper.class))).thenReturn(schedule(11L));
        when(authService.isStaffMember(8L, 3L)).thenReturn(true);
        when(shiftMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThrows(RuntimeException.class, () -> service.createShift(11L, new OnCallService.ShiftCommand(8L,
                LocalDateTime.of(2026, 9, 28, 9, 0), LocalDateTime.of(2026, 9, 28, 17, 0),
                true, null), 7L, 3L));

        verify(shiftMapper, never()).insert(any(OnCallShift.class));
    }

    @Test
    void createsOnCallEscalationStepBoundToSchedule() {
        OnCallEscalationPolicy policy = new OnCallEscalationPolicy();
        policy.setId(21L);
        policy.setTenantId(3L);
        policy.setName("生产 SEV2 值班升级");
        policy.setMinimumSeverity(IncidentSeverity.SEV2);
        when(policyMapper.selectOne(any(Wrapper.class))).thenReturn(policy);
        when(scheduleMapper.selectOne(any(Wrapper.class))).thenReturn(schedule(11L));
        when(stepMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stepMapper.insert(any(OnCallEscalationStep.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, OnCallEscalationStep.class).setId(41L);
            return 1;
        });

        OnCallEscalationStep result = service.createStep(21L,
                new OnCallService.StepCommand(1, 0, OnCallEscalationTargetType.ON_CALL, 11L, null),
                7L, 3L);

        assertEquals(1, result.getStepNo());
        assertEquals(OnCallEscalationTargetType.ON_CALL, result.getTargetType());
        assertEquals(11L, result.getScheduleId());
        verify(auditLogService).record(eq(3L), eq(7L), eq("ONCALL_POLICY"), eq(21L),
                eq("ONCALL_ESCALATION_STEP_CREATED"), eq(null), eq(result), contains("新增"));
    }

    private OnCallSchedule schedule(Long id) {
        OnCallSchedule schedule = new OnCallSchedule();
        schedule.setId(id);
        schedule.setTenantId(3L);
        schedule.setName("订单服务一线值班");
        schedule.setEnabled(true);
        return schedule;
    }
}
