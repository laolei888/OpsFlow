package com.opsflow.dashboard;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/overview")
    public ApiResponse<DashboardOverview> overview(@AuthenticationPrincipal AuthPrincipal principal) {
        Long requesterId = principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
        return ApiResponse.ok(dashboardService.overview(principal.tenantId(), requesterId));
    }

    @GetMapping("/report")
    public ApiResponse<DashboardReport> report(@RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以查看运营报表");
        }
        return ApiResponse.ok(dashboardService.report(principal.tenantId(), from, to));
    }
    @GetMapping("/trends")
    public ApiResponse<List<DashboardTrend>> trends(@RequestParam(defaultValue = "14") Integer days,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        Long requesterId = principal.hasAnyRole("AGENT", "ADMIN") ? null : principal.id();
        return ApiResponse.ok(dashboardService.trends(principal.tenantId(), requesterId, days));
    }
}
