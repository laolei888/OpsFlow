package com.opsflow.webhook;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/webhooks")
@RequiredArgsConstructor
public class WebhookController {
    private final WebhookEndpointService endpointService;
    private final WebhookOutboxService outboxService;

    @GetMapping
    public ApiResponse<List<WebhookEndpointService.WebhookEndpointView>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(endpointService.list(principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<WebhookEndpointService.WebhookEndpointView> create(
            @Valid @RequestBody WebhookRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(endpointService.create(request.command(), principal.tenantId(), principal.id()),
                "Webhook 集成已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<WebhookEndpointService.WebhookEndpointView> update(
            @PathVariable Long id, @Valid @RequestBody WebhookRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(endpointService.update(id, request.command(), principal.tenantId(), principal.id()),
                "Webhook 集成已更新");
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<WebhookEndpointService.WebhookEndpointView> toggle(
            @PathVariable Long id, @RequestBody(required = false) ToggleRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(endpointService.toggle(id, request == null ? null : request.enabled(),
                principal.tenantId(), principal.id()), "Webhook 状态已更新");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        endpointService.delete(id, principal.tenantId(), principal.id());
        return ApiResponse.ok(null, "Webhook 集成已删除");
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<WebhookEndpointService.WebhookDeliveryView>> deliveries(
            @RequestParam(required = false) Long endpointId,
            @RequestParam(required = false) WebhookDeliveryStatus status,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(endpointService.listDeliveries(principal.tenantId(), endpointId, status, limit));
    }

    @PostMapping("/deliveries/{id}/retry")
    public ApiResponse<WebhookEndpointService.WebhookDeliveryView> retry(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(endpointService.retry(id, principal.tenantId(), principal.id()), "Webhook 已重新排队");
    }

    @GetMapping("/outbox")
    public ApiResponse<List<WebhookOutboxService.OutboxEventView>> outbox(
            @RequestParam(required = false) WebhookOutboxStatus status,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.list(principal.tenantId(), status, limit));
    }

    @GetMapping("/outbox/summary")
    public ApiResponse<WebhookOutboxService.OutboxSummary> outboxSummary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.summary(principal.tenantId()));
    }

    @PostMapping("/outbox/{id}/retry")
    public ApiResponse<WebhookOutboxEvent> retryOutbox(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(outboxService.retry(id, principal.tenantId(), principal.id()),
                "Outbox 事件已重新排队");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理 Webhook 集成");
        }
    }

    public record WebhookRequest(@NotBlank @Size(max = 80) String name,
                                 @NotBlank @Size(max = 500) String url,
                                 @Size(max = 200) String secret,
                                 @NotBlank @Size(max = 2000) String eventTypes) {
        WebhookEndpointService.WebhookCommand command() {
            return new WebhookEndpointService.WebhookCommand(name, url, secret, eventTypes);
        }
    }

    public record ToggleRequest(Boolean enabled) { }
}
