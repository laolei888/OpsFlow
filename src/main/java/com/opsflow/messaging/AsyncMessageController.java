package com.opsflow.messaging;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
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
            @RequestParam(required = false) String notificationType,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.list(principal.tenantId(), status,
                notificationType, aggregateType, keyword, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<AsyncMessageOutboxService.OutboxSummary> summary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.summary(principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AsyncMessageOutboxService.OutboxDiagnostic> diagnostic(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.diagnostic(id, principal.tenantId()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<AsyncMessageOutboxEvent> retry(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.retry(id, principal.tenantId(), principal.id()),
                "异步消息已重新排队");
    }

    @PostMapping("/retry")
    public ApiResponse<Integer> retryBatch(@Valid @RequestBody RetryBatchRequest request,
                                           @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.retryBatch(request.ids(), principal.tenantId(), principal.id()),
                "选中的异步消息已重新排队");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理异步消息");
        }
    }

    public record RetryBatchRequest(@NotEmpty @Size(max = 100) List<Long> ids) { }
}
