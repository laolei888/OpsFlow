package com.opsflow.capacity;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/capacity/alerts")
@RequiredArgsConstructor
public class CapacityAlertController {
    private final CapacityAlertService alertService;

    @GetMapping
    public ApiResponse<List<CapacityAlert>> list(@RequestParam(required = false) CapacityAlertStatus status,
                                                  @RequestParam(required = false) CapacityAlertLevel level,
                                                  @RequestParam(required = false) Integer limit,
                                                  @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(alertService.list(principal.tenantId(), status, level, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<CapacityAlertService.AlertSummary> summary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(alertService.summary(principal.tenantId()));
    }

    @GetMapping("/history")
    public ApiResponse<CapacityAlertService.AlertHistoryReport> history(
            @RequestParam(required = false) Integer days,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(alertService.history(principal.tenantId(), days));
    }

    @PostMapping("/{id}/acknowledge")
    public ApiResponse<CapacityAlert> acknowledge(@PathVariable Long id,
                                                   @Valid @RequestBody(required = false) AcknowledgeRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(alertService.acknowledge(id, request == null ? null : request.note(),
                principal.tenantId(), principal.id()), "容量预警已确认");
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<CapacityAlert> resolve(@PathVariable Long id,
                                               @Valid @RequestBody ResolveRequest request,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(alertService.resolve(id, request.note(), principal.tenantId(), principal.id()),
                "容量预警已解决");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以处置容量预警");
        }
    }

    public record AcknowledgeRequest(@Size(max = 500) String note) { }
    public record ResolveRequest(@NotBlank @Size(min = 5, max = 500) String note) { }
}
