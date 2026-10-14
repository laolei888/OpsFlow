package com.opsflow.oncall;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.IncidentSeverity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/oncall")
@RequiredArgsConstructor
public class OnCallController {
    private final OnCallService onCallService;
    private final OnCallEscalationService escalationService;

    @GetMapping("/schedules")
    public ApiResponse<List<OnCallSchedule>> schedules(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(onCallService.listSchedules(principal.tenantId()));
    }

    @PostMapping("/schedules")
    public ApiResponse<OnCallSchedule> createSchedule(@Valid @RequestBody ScheduleRequest request,
                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.createSchedule(request.command(), principal.id(), principal.tenantId()),
                "值班表已创建");
    }

    @PutMapping("/schedules/{id}")
    public ApiResponse<OnCallSchedule> updateSchedule(@PathVariable Long id, @Valid @RequestBody ScheduleRequest request,
                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.updateSchedule(id, request.command(), principal.id(), principal.tenantId()),
                "值班表已更新");
    }

    @GetMapping("/shifts")
    public ApiResponse<List<OnCallShift>> shifts(@RequestParam(required = false) Long scheduleId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(onCallService.listShifts(principal.tenantId(), scheduleId, from, to));
    }

    @PostMapping("/schedules/{scheduleId}/shifts")
    public ApiResponse<OnCallShift> createShift(@PathVariable Long scheduleId, @Valid @RequestBody ShiftRequest request,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.createShift(scheduleId, request.command(), principal.id(), principal.tenantId()),
                "值班轮班已创建");
    }

    @PutMapping("/shifts/{id}")
    public ApiResponse<OnCallShift> updateShift(@PathVariable Long id, @Valid @RequestBody ShiftRequest request,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.updateShift(id, request.command(), principal.id(), principal.tenantId()),
                "值班轮班已更新");
    }

    @DeleteMapping("/shifts/{id}")
    public ApiResponse<Void> deleteShift(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        onCallService.deleteShift(id, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "值班轮班已删除");
    }

    @GetMapping("/policies")
    public ApiResponse<List<OnCallService.PolicyView>> policies(@AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(onCallService.listPolicies(principal.tenantId()));
    }

    @PostMapping("/policies")
    public ApiResponse<OnCallEscalationPolicy> createPolicy(@Valid @RequestBody PolicyRequest request,
                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.createPolicy(request.command(), principal.id(), principal.tenantId()),
                "升级策略已创建");
    }

    @PutMapping("/policies/{id}")
    public ApiResponse<OnCallEscalationPolicy> updatePolicy(@PathVariable Long id, @Valid @RequestBody PolicyRequest request,
                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.updatePolicy(id, request.command(), principal.id(), principal.tenantId()),
                "升级策略已更新");
    }

    @PostMapping("/policies/{policyId}/steps")
    public ApiResponse<OnCallEscalationStep> createStep(@PathVariable Long policyId, @Valid @RequestBody StepRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.createStep(policyId, request.command(), principal.id(), principal.tenantId()),
                "升级步骤已创建");
    }

    @PutMapping("/steps/{id}")
    public ApiResponse<OnCallEscalationStep> updateStep(@PathVariable Long id, @Valid @RequestBody StepRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(onCallService.updateStep(id, request.command(), principal.id(), principal.tenantId()),
                "升级步骤已更新");
    }

    @DeleteMapping("/steps/{id}")
    public ApiResponse<Void> deleteStep(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        onCallService.deleteStep(id, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "升级步骤已删除");
    }

    @GetMapping("/escalation-events")
    public ApiResponse<List<OnCallEscalationService.EscalationEventView>> escalationEvents(
            @RequestParam(defaultValue = "80") Integer limit, @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(escalationService.listEvents(principal.tenantId(), limit));
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以查看值班与升级中心");
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置值班与升级中心");
        }
    }

    public record ScheduleRequest(@NotBlank @Size(max = 100) String name,
                                  @Size(max = 1000) String description,
                                  @Size(max = 64) String timezone,
                                  Boolean enabled) {
        OnCallService.ScheduleCommand command() {
            return new OnCallService.ScheduleCommand(name, description, timezone, enabled);
        }
    }

    public record ShiftRequest(@NotNull Long userId,
                               @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
                               @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
                               Boolean primary,
                               @Size(max = 1000) String handoffNote) {
        OnCallService.ShiftCommand command() {
            return new OnCallService.ShiftCommand(userId, startAt, endAt, primary, handoffNote);
        }
    }

    public record PolicyRequest(@NotBlank @Size(max = 100) String name,
                                @Size(max = 1000) String description,
                                IncidentSeverity minimumSeverity,
                                Boolean enabled) {
        OnCallService.PolicyCommand command() {
            return new OnCallService.PolicyCommand(name, description, minimumSeverity, enabled);
        }
    }

    public record StepRequest(@NotNull @Min(1) @Max(10) Integer stepNo,
                              @Min(0) @Max(1440) Integer delayMinutes,
                              @NotNull OnCallEscalationTargetType targetType,
                              Long scheduleId, Long targetUserId) {
        OnCallService.StepCommand command() {
            return new OnCallService.StepCommand(stepNo, delayMinutes, targetType, scheduleId, targetUserId);
        }
    }
}
