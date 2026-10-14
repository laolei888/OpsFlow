package com.opsflow.sla;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/sla-policy")
@RequiredArgsConstructor
public class SlaPolicyController {
    private final SlaPolicyService slaPolicyService;

    @GetMapping
    public ApiResponse<SlaPolicyView> get(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(slaPolicyService.get(principal.tenantId()));
    }

    @PutMapping
    public ApiResponse<SlaPolicyView> update(@Valid @RequestBody UpdateSlaPolicyRequest request,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(slaPolicyService.update(principal.tenantId(), request.urgentHours(),
                request.highHours(), request.mediumHours(), request.lowHours(), principal.id()),
                "SLA 策略已更新");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以配置 SLA 策略");
        }
    }

    public record UpdateSlaPolicyRequest(
            @Min(1) @Max(720) int urgentHours,
            @Min(1) @Max(720) int highHours,
            @Min(1) @Max(720) int mediumHours,
            @Min(1) @Max(720) int lowHours) { }
}
