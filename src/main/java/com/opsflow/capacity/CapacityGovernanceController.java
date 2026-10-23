package com.opsflow.capacity;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/capacity")
@RequiredArgsConstructor
public class CapacityGovernanceController {
    private final CapacityPolicyService policyService;
    private final CapacityMaintenanceWindowService maintenanceWindowService;

    @GetMapping("/policy")
    public ApiResponse<CapacityPolicyService.EffectivePolicy> policy(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(policyService.effective(principal.tenantId()));
    }

    @PutMapping("/policy")
    public ApiResponse<CapacityPolicyService.EffectivePolicy> updatePolicy(
            @Valid @RequestBody PolicyRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(policyService.save(request.command(), principal.tenantId(), principal.id()),
                "容量告警策略已更新");
    }

    @GetMapping("/maintenance-windows")
    public ApiResponse<List<CapacityMaintenanceWindow>> maintenanceWindows(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(maintenanceWindowService.list(principal.tenantId()));
    }

    @PostMapping("/maintenance-windows")
    public ApiResponse<CapacityMaintenanceWindow> createMaintenanceWindow(
            @Valid @RequestBody MaintenanceWindowRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(maintenanceWindowService.create(request.command(), principal.tenantId(), principal.id()),
                "容量维护窗口已创建");
    }

    @PutMapping("/maintenance-windows/{id}")
    public ApiResponse<CapacityMaintenanceWindow> updateMaintenanceWindow(
            @PathVariable Long id, @Valid @RequestBody MaintenanceWindowRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(maintenanceWindowService.update(id, request.command(),
                principal.tenantId(), principal.id()), "容量维护窗口已更新");
    }

    @DeleteMapping("/maintenance-windows/{id}")
    public ApiResponse<Void> deleteMaintenanceWindow(@PathVariable Long id,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        maintenanceWindowService.delete(id, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "容量维护窗口已删除");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置容量治理策略");
        }
    }

    public record PolicyRequest(
            @NotNull @PositiveOrZero Long activeTicketsThreshold,
            @NotNull @PositiveOrZero Long overdueTicketsThreshold,
            @NotNull @PositiveOrZero Long activeIncidentsThreshold,
            @NotNull @PositiveOrZero Long firingAlertsThreshold,
            @NotNull @PositiveOrZero Long asyncBacklogThreshold,
            @NotNull @PositiveOrZero Long attachmentBacklogThreshold,
            @NotNull @PositiveOrZero Long attachmentBytesThreshold,
            @NotNull @PositiveOrZero Long databaseBytesThreshold,
            @NotNull @Min(50) @Max(99) Integer warningPercent,
            @NotNull @Min(1) @Max(365) Integer watchDays,
            Boolean enabled) {
        CapacityPolicyService.PolicyCommand command() {
            return new CapacityPolicyService.PolicyCommand(activeTicketsThreshold, overdueTicketsThreshold,
                    activeIncidentsThreshold, firingAlertsThreshold, asyncBacklogThreshold,
                    attachmentBacklogThreshold, attachmentBytesThreshold, databaseBytesThreshold,
                    warningPercent, watchDays, enabled);
        }
    }

    public record MaintenanceWindowRequest(@NotBlank @Size(max = 120) String name,
                                           @NotNull LocalDateTime startsAt,
                                           @NotNull LocalDateTime endsAt,
                                           @NotBlank @Size(max = 1000) String reason,
                                           Boolean enabled) {
        CapacityMaintenanceWindowService.WindowCommand command() {
            return new CapacityMaintenanceWindowService.WindowCommand(name, startsAt, endsAt, reason, enabled);
        }
    }
}
