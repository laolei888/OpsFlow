package com.opsflow.dashboard;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard/report-subscriptions")
@RequiredArgsConstructor
public class DashboardReportSubscriptionController {
    private final DashboardReportSubscriptionService subscriptionService;

    @GetMapping
    public ApiResponse<List<DashboardReportSubscription>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(subscriptionService.list(principal.id(), principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<DashboardReportSubscription> create(
            @Valid @RequestBody CreateSubscriptionRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(subscriptionService.create(
                request.name(), request.frequency(), request.rangeDays(),
                principal.id(), principal.tenantId()), "报表订阅已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<DashboardReportSubscription> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSubscriptionRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(subscriptionService.update(
                id, request.name(), request.frequency(), request.rangeDays(), request.enabled(),
                principal.id(), principal.tenantId()), "报表订阅已更新");
    }

    @PostMapping("/{id}/run")
    public ApiResponse<DashboardReportSubscription> run(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(subscriptionService.runNow(id, principal.id(), principal.tenantId()),
                "报表摘要已发送到通知中心");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        subscriptionService.delete(id, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "报表订阅已删除");
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以管理报表订阅");
        }
    }

    public record CreateSubscriptionRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank String frequency,
            @NotNull @Min(7) @Max(30) Integer rangeDays) { }

    public record UpdateSubscriptionRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank String frequency,
            @NotNull @Min(7) @Max(30) Integer rangeDays,
            @NotNull Boolean enabled) { }
}