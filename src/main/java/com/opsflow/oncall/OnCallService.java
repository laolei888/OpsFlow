package com.opsflow.oncall;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OnCallService {
    private final OnCallScheduleMapper scheduleMapper;
    private final OnCallShiftMapper shiftMapper;
    private final OnCallEscalationPolicyMapper policyMapper;
    private final OnCallEscalationStepMapper stepMapper;
    private final AuthService authService;
    private final AuditLogService auditLogService;

    public List<OnCallSchedule> listSchedules(Long tenantId) {
        return scheduleMapper.selectList(new LambdaQueryWrapper<OnCallSchedule>()
                .eq(OnCallSchedule::getTenantId, tenantId)
                .orderByDesc(OnCallSchedule::getEnabled)
                .orderByAsc(OnCallSchedule::getName)
                .orderByAsc(OnCallSchedule::getId));
    }

    public List<OnCallShift> listShifts(Long tenantId, Long scheduleId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime safeFrom = from == null ? LocalDateTime.now().minusDays(1) : from;
        LocalDateTime safeTo = to == null ? LocalDateTime.now().plusDays(14) : to;
        if (!safeTo.isAfter(safeFrom)) throw new BusinessException("轮班结束时间必须晚于开始时间");
        if (Duration.between(safeFrom, safeTo).toDays() > 93) throw new BusinessException("单次最多查询 93 天值班安排");
        LambdaQueryWrapper<OnCallShift> query = new LambdaQueryWrapper<OnCallShift>()
                .eq(OnCallShift::getTenantId, tenantId)
                .lt(OnCallShift::getStartAt, safeTo)
                .gt(OnCallShift::getEndAt, safeFrom)
                .orderByAsc(OnCallShift::getStartAt)
                .orderByAsc(OnCallShift::getId)
                .last("LIMIT 500");
        if (scheduleId != null) query.eq(OnCallShift::getScheduleId, scheduleId);
        return shiftMapper.selectList(query);
    }

    @Transactional
    public OnCallSchedule createSchedule(ScheduleCommand command, Long actorId, Long tenantId) {
        validateScheduleCommand(command);
        String name = normalize(command.name());
        if (scheduleMapper.selectCount(new LambdaQueryWrapper<OnCallSchedule>()
                .eq(OnCallSchedule::getTenantId, tenantId)
                .eq(OnCallSchedule::getName, name)) > 0) {
            throw new BusinessException("当前租户已存在同名值班表");
        }
        LocalDateTime now = LocalDateTime.now();
        OnCallSchedule schedule = new OnCallSchedule();
        schedule.setTenantId(tenantId);
        schedule.setName(name);
        schedule.setDescription(normalize(command.description()));
        schedule.setTimezone(normalizeZone(command.timezone()));
        schedule.setEnabled(command.enabled() == null || command.enabled());
        schedule.setCreatedBy(actorId);
        schedule.setCreatedAt(now);
        schedule.setUpdatedAt(now);
        scheduleMapper.insert(schedule);
        auditLogService.record(tenantId, actorId, "ONCALL_SCHEDULE", schedule.getId(), "ONCALL_SCHEDULE_CREATED",
                null, schedule, "创建值班表");
        return schedule;
    }

    @Transactional
    public OnCallSchedule updateSchedule(Long id, ScheduleCommand command, Long actorId, Long tenantId) {
        validateScheduleCommand(command);
        OnCallSchedule schedule = findSchedule(id, tenantId);
        OnCallSchedule before = copy(schedule);
        String name = normalize(command.name());
        if (scheduleMapper.selectCount(new LambdaQueryWrapper<OnCallSchedule>()
                .eq(OnCallSchedule::getTenantId, tenantId)
                .eq(OnCallSchedule::getName, name)
                .ne(OnCallSchedule::getId, id)) > 0) {
            throw new BusinessException("当前租户已存在同名值班表");
        }
        schedule.setName(name);
        schedule.setDescription(normalize(command.description()));
        schedule.setTimezone(normalizeZone(command.timezone()));
        schedule.setEnabled(command.enabled() == null || command.enabled());
        schedule.setUpdatedAt(LocalDateTime.now());
        scheduleMapper.updateById(schedule);
        auditLogService.record(tenantId, actorId, "ONCALL_SCHEDULE", id, "ONCALL_SCHEDULE_UPDATED",
                before, schedule, "更新值班表");
        return schedule;
    }

    @Transactional
    public OnCallShift createShift(Long scheduleId, ShiftCommand command, Long actorId, Long tenantId) {
        OnCallSchedule schedule = findSchedule(scheduleId, tenantId);
        validateShiftCommand(command, scheduleId, null, tenantId);
        LocalDateTime now = LocalDateTime.now();
        OnCallShift shift = new OnCallShift();
        shift.setTenantId(tenantId);
        shift.setScheduleId(schedule.getId());
        shift.setUserId(command.userId());
        shift.setStartAt(command.startAt());
        shift.setEndAt(command.endAt());
        shift.setPrimary(command.primary() == null || command.primary());
        shift.setHandoffNote(normalize(command.handoffNote()));
        shift.setCreatedBy(actorId);
        shift.setCreatedAt(now);
        shift.setUpdatedAt(now);
        shiftMapper.insert(shift);
        auditLogService.record(tenantId, actorId, "ONCALL_SHIFT", shift.getId(), "ONCALL_SHIFT_CREATED",
                null, shift, "创建值班轮班");
        return shift;
    }

    @Transactional
    public OnCallShift updateShift(Long id, ShiftCommand command, Long actorId, Long tenantId) {
        OnCallShift shift = findShift(id, tenantId);
        validateShiftCommand(command, shift.getScheduleId(), id, tenantId);
        OnCallShift before = copy(shift);
        shift.setUserId(command.userId());
        shift.setStartAt(command.startAt());
        shift.setEndAt(command.endAt());
        shift.setPrimary(command.primary() == null || command.primary());
        shift.setHandoffNote(normalize(command.handoffNote()));
        shift.setUpdatedAt(LocalDateTime.now());
        shiftMapper.updateById(shift);
        auditLogService.record(tenantId, actorId, "ONCALL_SHIFT", id, "ONCALL_SHIFT_UPDATED",
                before, shift, "更新值班轮班");
        return shift;
    }

    @Transactional
    public void deleteShift(Long id, Long actorId, Long tenantId) {
        OnCallShift shift = findShift(id, tenantId);
        shiftMapper.deleteById(id);
        auditLogService.record(tenantId, actorId, "ONCALL_SHIFT", id, "ONCALL_SHIFT_DELETED",
                shift, null, "删除值班轮班");
    }

    public List<PolicyView> listPolicies(Long tenantId) {
        List<OnCallEscalationPolicy> policies = policyMapper.selectList(new LambdaQueryWrapper<OnCallEscalationPolicy>()
                .eq(OnCallEscalationPolicy::getTenantId, tenantId)
                .orderByDesc(OnCallEscalationPolicy::getEnabled)
                .orderByAsc(OnCallEscalationPolicy::getName)
                .orderByAsc(OnCallEscalationPolicy::getId));
        if (policies.isEmpty()) return List.of();
        Set<Long> policyIds = policies.stream().map(OnCallEscalationPolicy::getId).collect(Collectors.toSet());
        Map<Long, List<OnCallEscalationStep>> stepsByPolicy = stepMapper.selectList(new LambdaQueryWrapper<OnCallEscalationStep>()
                        .eq(OnCallEscalationStep::getTenantId, tenantId)
                        .in(OnCallEscalationStep::getPolicyId, policyIds)
                        .orderByAsc(OnCallEscalationStep::getStepNo)
                        .orderByAsc(OnCallEscalationStep::getId))
                .stream().collect(Collectors.groupingBy(OnCallEscalationStep::getPolicyId));
        return policies.stream().map(policy -> new PolicyView(policy,
                stepsByPolicy.getOrDefault(policy.getId(), List.of()))).toList();
    }

    @Transactional
    public OnCallEscalationPolicy createPolicy(PolicyCommand command, Long actorId, Long tenantId) {
        validatePolicyCommand(command);
        String name = normalize(command.name());
        if (policyMapper.selectCount(new LambdaQueryWrapper<OnCallEscalationPolicy>()
                .eq(OnCallEscalationPolicy::getTenantId, tenantId)
                .eq(OnCallEscalationPolicy::getName, name)) > 0) {
            throw new BusinessException("当前租户已存在同名升级策略");
        }
        LocalDateTime now = LocalDateTime.now();
        OnCallEscalationPolicy policy = new OnCallEscalationPolicy();
        policy.setTenantId(tenantId);
        policy.setName(name);
        policy.setDescription(normalize(command.description()));
        policy.setMinimumSeverity(command.minimumSeverity() == null ? IncidentSeverity.SEV2 : command.minimumSeverity());
        policy.setEnabled(command.enabled() == null || command.enabled());
        policy.setCreatedBy(actorId);
        policy.setCreatedAt(now);
        policy.setUpdatedAt(now);
        policyMapper.insert(policy);
        auditLogService.record(tenantId, actorId, "ONCALL_POLICY", policy.getId(), "ONCALL_POLICY_CREATED",
                null, policy, "创建升级策略");
        return policy;
    }

    @Transactional
    public OnCallEscalationPolicy updatePolicy(Long id, PolicyCommand command, Long actorId, Long tenantId) {
        validatePolicyCommand(command);
        OnCallEscalationPolicy policy = findPolicy(id, tenantId);
        OnCallEscalationPolicy before = copy(policy);
        String name = normalize(command.name());
        if (policyMapper.selectCount(new LambdaQueryWrapper<OnCallEscalationPolicy>()
                .eq(OnCallEscalationPolicy::getTenantId, tenantId)
                .eq(OnCallEscalationPolicy::getName, name)
                .ne(OnCallEscalationPolicy::getId, id)) > 0) {
            throw new BusinessException("当前租户已存在同名升级策略");
        }
        policy.setName(name);
        policy.setDescription(normalize(command.description()));
        policy.setMinimumSeverity(command.minimumSeverity() == null ? IncidentSeverity.SEV2 : command.minimumSeverity());
        policy.setEnabled(command.enabled() == null || command.enabled());
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        auditLogService.record(tenantId, actorId, "ONCALL_POLICY", id, "ONCALL_POLICY_UPDATED",
                before, policy, "更新升级策略");
        return policy;
    }

    @Transactional
    public OnCallEscalationStep createStep(Long policyId, StepCommand command, Long actorId, Long tenantId) {
        findPolicy(policyId, tenantId);
        validateStepCommand(command, policyId, null, tenantId);
        LocalDateTime now = LocalDateTime.now();
        OnCallEscalationStep step = new OnCallEscalationStep();
        step.setTenantId(tenantId);
        step.setPolicyId(policyId);
        populateStep(step, command, tenantId);
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        stepMapper.insert(step);
        auditLogService.record(tenantId, actorId, "ONCALL_POLICY", policyId, "ONCALL_ESCALATION_STEP_CREATED",
                null, step, "新增升级步骤");
        return step;
    }

    @Transactional
    public OnCallEscalationStep updateStep(Long id, StepCommand command, Long actorId, Long tenantId) {
        OnCallEscalationStep step = findStep(id, tenantId);
        validateStepCommand(command, step.getPolicyId(), id, tenantId);
        OnCallEscalationStep before = copy(step);
        populateStep(step, command, tenantId);
        step.setUpdatedAt(LocalDateTime.now());
        stepMapper.updateById(step);
        auditLogService.record(tenantId, actorId, "ONCALL_POLICY", step.getPolicyId(), "ONCALL_ESCALATION_STEP_UPDATED",
                before, step, "更新升级步骤");
        return step;
    }

    @Transactional
    public void deleteStep(Long id, Long actorId, Long tenantId) {
        OnCallEscalationStep step = findStep(id, tenantId);
        stepMapper.deleteById(id);
        auditLogService.record(tenantId, actorId, "ONCALL_POLICY", step.getPolicyId(), "ONCALL_ESCALATION_STEP_DELETED",
                step, null, "删除升级步骤");
    }

    private void populateStep(OnCallEscalationStep step, StepCommand command, Long tenantId) {
        step.setStepNo(command.stepNo());
        step.setDelayMinutes(command.delayMinutes() == null ? 0 : command.delayMinutes());
        step.setTargetType(command.targetType());
        if (command.targetType() == OnCallEscalationTargetType.ON_CALL) {
            step.setScheduleId(command.scheduleId());
            step.setTargetUserId(null);
        } else if (command.targetType() == OnCallEscalationTargetType.USER) {
            step.setScheduleId(null);
            step.setTargetUserId(command.targetUserId());
        } else {
            step.setScheduleId(null);
            step.setTargetUserId(null);
        }
    }

    private void validateScheduleCommand(ScheduleCommand command) {
        if (command == null || normalize(command.name()) == null || normalize(command.name()).length() > 100) {
            throw new BusinessException("值班表名称不能为空且不能超过 100 个字符");
        }
        validateLength(command.description(), 1000, "值班表说明");
        normalizeZone(command.timezone());
    }

    private void validateShiftCommand(ShiftCommand command, Long scheduleId, Long ignoreId, Long tenantId) {
        if (command == null || command.userId() == null || command.startAt() == null || command.endAt() == null) {
            throw new BusinessException("值班人员、开始时间和结束时间不能为空");
        }
        if (!command.endAt().isAfter(command.startAt())) throw new BusinessException("轮班结束时间必须晚于开始时间");
        if (Duration.between(command.startAt(), command.endAt()).toDays() > 31) {
            throw new BusinessException("单个值班轮班不能超过 31 天");
        }
        if (!authService.isStaffMember(command.userId(), tenantId)) {
            throw new BusinessException("值班人员不存在或不是当前租户的客服账号");
        }
        validateLength(command.handoffNote(), 1000, "交接备注");
        if (command.primary() == null || command.primary()) {
            LambdaQueryWrapper<OnCallShift> overlap = new LambdaQueryWrapper<OnCallShift>()
                    .eq(OnCallShift::getTenantId, tenantId)
                    .eq(OnCallShift::getScheduleId, scheduleId)
                    .eq(OnCallShift::getPrimaryFlag, true)
                    .lt(OnCallShift::getStartAt, command.endAt())
                    .gt(OnCallShift::getEndAt, command.startAt());
            if (ignoreId != null) overlap.ne(OnCallShift::getId, ignoreId);
            if (shiftMapper.selectCount(overlap) > 0) {
                throw new BusinessException("该值班表在此时间段已有主值班人员，请调整轮班时间或设置为备份值班");
            }
        }
    }

    private void validatePolicyCommand(PolicyCommand command) {
        if (command == null || normalize(command.name()) == null || normalize(command.name()).length() > 100) {
            throw new BusinessException("升级策略名称不能为空且不能超过 100 个字符");
        }
        validateLength(command.description(), 1000, "升级策略说明");
    }

    private void validateStepCommand(StepCommand command, Long policyId, Long ignoreId, Long tenantId) {
        if (command == null || command.stepNo() == null || command.stepNo() < 1 || command.stepNo() > 10) {
            throw new BusinessException("升级步骤序号必须在 1-10 之间");
        }
        int delay = command.delayMinutes() == null ? 0 : command.delayMinutes();
        if (delay < 0 || delay > 1440) throw new BusinessException("升级延迟必须在 0-1440 分钟之间");
        if (command.targetType() == null) throw new BusinessException("请选择升级接收目标");
        if (stepMapper.selectCount(new LambdaQueryWrapper<OnCallEscalationStep>()
                .eq(OnCallEscalationStep::getTenantId, tenantId)
                .eq(OnCallEscalationStep::getPolicyId, policyId)
                .eq(OnCallEscalationStep::getStepNo, command.stepNo())
                .ne(ignoreId != null, OnCallEscalationStep::getId, ignoreId)) > 0) {
            throw new BusinessException("该升级策略已存在相同序号的步骤");
        }
        if (command.targetType() == OnCallEscalationTargetType.ON_CALL) {
            if (command.scheduleId() == null) throw new BusinessException("请选择值班表作为升级接收目标");
            findSchedule(command.scheduleId(), tenantId);
        }
        if (command.targetType() == OnCallEscalationTargetType.USER
                && (command.targetUserId() == null || !authService.isStaffMember(command.targetUserId(), tenantId))) {
            throw new BusinessException("升级接收人不存在或不是当前租户的客服账号");
        }
    }

    private OnCallSchedule findSchedule(Long id, Long tenantId) {
        OnCallSchedule schedule = scheduleMapper.selectOne(new LambdaQueryWrapper<OnCallSchedule>()
                .eq(OnCallSchedule::getId, id)
                .eq(OnCallSchedule::getTenantId, tenantId));
        if (schedule == null) throw new BusinessException("值班表不存在");
        return schedule;
    }

    private OnCallShift findShift(Long id, Long tenantId) {
        OnCallShift shift = shiftMapper.selectOne(new LambdaQueryWrapper<OnCallShift>()
                .eq(OnCallShift::getId, id)
                .eq(OnCallShift::getTenantId, tenantId));
        if (shift == null) throw new BusinessException("值班轮班不存在");
        return shift;
    }

    private OnCallEscalationPolicy findPolicy(Long id, Long tenantId) {
        OnCallEscalationPolicy policy = policyMapper.selectOne(new LambdaQueryWrapper<OnCallEscalationPolicy>()
                .eq(OnCallEscalationPolicy::getId, id)
                .eq(OnCallEscalationPolicy::getTenantId, tenantId));
        if (policy == null) throw new BusinessException("升级策略不存在");
        return policy;
    }

    private OnCallEscalationStep findStep(Long id, Long tenantId) {
        OnCallEscalationStep step = stepMapper.selectOne(new LambdaQueryWrapper<OnCallEscalationStep>()
                .eq(OnCallEscalationStep::getId, id)
                .eq(OnCallEscalationStep::getTenantId, tenantId));
        if (step == null) throw new BusinessException("升级步骤不存在");
        return step;
    }

    private String normalizeZone(String source) {
        String zone = normalize(source);
        String value = zone == null ? "Asia/Shanghai" : zone;
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException ex) {
            throw new BusinessException("无效的时区标识");
        }
    }

    private void validateLength(String value, int max, String field) {
        if (value != null && value.trim().length() > max) throw new BusinessException(field + "不能超过 " + max + " 个字符");
    }

    private String normalize(String source) {
        if (source == null) return null;
        String result = source.trim();
        return result.isBlank() ? null : result;
    }

    private OnCallSchedule copy(OnCallSchedule source) {
        OnCallSchedule result = new OnCallSchedule();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setName(source.getName());
        result.setDescription(source.getDescription());
        result.setTimezone(source.getTimezone());
        result.setEnabled(source.getEnabled());
        result.setCreatedBy(source.getCreatedBy());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private OnCallShift copy(OnCallShift source) {
        OnCallShift result = new OnCallShift();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setScheduleId(source.getScheduleId());
        result.setUserId(source.getUserId());
        result.setStartAt(source.getStartAt());
        result.setEndAt(source.getEndAt());
        result.setPrimary(source.getPrimary());
        result.setHandoffNote(source.getHandoffNote());
        result.setCreatedBy(source.getCreatedBy());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private OnCallEscalationPolicy copy(OnCallEscalationPolicy source) {
        OnCallEscalationPolicy result = new OnCallEscalationPolicy();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setName(source.getName());
        result.setDescription(source.getDescription());
        result.setMinimumSeverity(source.getMinimumSeverity());
        result.setEnabled(source.getEnabled());
        result.setCreatedBy(source.getCreatedBy());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private OnCallEscalationStep copy(OnCallEscalationStep source) {
        OnCallEscalationStep result = new OnCallEscalationStep();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setPolicyId(source.getPolicyId());
        result.setStepNo(source.getStepNo());
        result.setDelayMinutes(source.getDelayMinutes());
        result.setTargetType(source.getTargetType());
        result.setScheduleId(source.getScheduleId());
        result.setTargetUserId(source.getTargetUserId());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    public record ScheduleCommand(String name, String description, String timezone, Boolean enabled) { }

    public record ShiftCommand(Long userId, LocalDateTime startAt, LocalDateTime endAt,
                               Boolean primary, String handoffNote) { }

    public record PolicyCommand(String name, String description, IncidentSeverity minimumSeverity,
                                Boolean enabled) { }

    public record StepCommand(Integer stepNo, Integer delayMinutes, OnCallEscalationTargetType targetType,
                              Long scheduleId, Long targetUserId) { }

    public record PolicyView(OnCallEscalationPolicy policy, List<OnCallEscalationStep> steps) { }
}
