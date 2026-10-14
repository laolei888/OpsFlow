package com.opsflow.workbench;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations-workbench")
public class OperationsWorkbenchController {
    private final OperationsWorkbenchService service;

    public OperationsWorkbenchController(OperationsWorkbenchService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<OperationsWorkbenchView> overview(@RequestParam(required = false) String types,
                                                          @RequestParam(required = false) String priorities,
                                                          @RequestParam(defaultValue = "100") Integer limit,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        requireStaff(principal);
        return ApiResponse.ok(service.overview(principal.tenantId(), principal.hasAnyRole("ADMIN"),
                types, priorities, limit));
    }

    private void requireStaff(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以访问运营待办中心");
        }
    }
}
