package com.opsflow.messaging;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/async-messages")
@RequiredArgsConstructor
public class AsyncMessageController {
    private final AsyncMessageOutboxService outboxService;

    @GetMapping
    public ApiResponse<List<AsyncMessageOutboxService.OutboxEventView>> list(
            @RequestParam(required = false) AsyncMessageOutboxStatus status,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.list(principal.tenantId(), status, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<AsyncMessageOutboxService.OutboxSummary> summary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.summary(principal.tenantId()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<AsyncMessageOutboxEvent> retry(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.retry(id, principal.tenantId(), principal.id()),
                "异步消息已重新排队");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理异步消息");
        }
    }
}
