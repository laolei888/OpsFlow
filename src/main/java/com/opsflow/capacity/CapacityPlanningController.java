package com.opsflow.capacity;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/capacity")
@RequiredArgsConstructor
public class CapacityPlanningController {
    private final CapacityPlanningService capacityPlanningService;

    @GetMapping("/report")
    public ApiResponse<CapacityPlanningService.CapacityReport> report(
            @RequestParam(required = false) Integer days,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(capacityPlanningService.report(principal.tenantId(), days));
    }

    @GetMapping("/comparison")
    public ApiResponse<CapacityPlanningService.CapacityComparison> comparison(
            @RequestParam Long baselineId, @RequestParam Long currentId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(capacityPlanningService.compare(baselineId, currentId, principal.tenantId()));
    }

    @PostMapping("/snapshots")
    public ApiResponse<CapacitySnapshot> capture(@Valid @RequestBody CaptureRequest request,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(capacityPlanningService.captureManual(request.command(), principal.tenantId(), principal.id()),
                "容量快照已创建，可用于版本基线比较");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以查看容量规划与版本基线");
        }
    }

    public record CaptureRequest(@Size(max = 120) String label,
                                 @Size(max = 64) String appVersion,
                                 Long releaseId) {
        CapacityPlanningService.CaptureCommand command() {
            return new CapacityPlanningService.CaptureCommand(label, appVersion, releaseId);
        }
    }
}
