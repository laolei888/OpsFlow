package com.opsflow.notification;

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
@RequestMapping("/api/v1/admin/notification-deliveries")
@RequiredArgsConstructor
public class NotificationDeliveryController {
    private final NotificationDeliveryService deliveryService;

    @GetMapping
    public ApiResponse<List<NotificationDeliveryService.DeliveryView>> list(
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) NotificationDeliveryStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deliveryService.search(principal.tenantId(), channel, status, keyword, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<NotificationDeliveryService.DeliverySummary> summary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deliveryService.summary(principal.tenantId()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<NotificationDeliveryService.DeliveryView> retry(
            @PathVariable Long id, @Valid @RequestBody RetryRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(deliveryService.retry(id, principal.tenantId(), principal.id(), request.reason()),
                "通知投递已重新排队");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理通知投递");
        }
    }

    public record RetryRequest(@NotBlank @Size(min = 5, max = 500) String reason) { }
}
