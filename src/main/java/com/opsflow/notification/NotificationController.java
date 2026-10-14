package com.opsflow.notification;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<Notification>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "30") @Min(1) int limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(notificationService.list(principal.id(), principal.tenantId(), unreadOnly, limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(notificationService.unreadCount(principal.id(), principal.tenantId()));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id,
                                      @AuthenticationPrincipal AuthPrincipal principal) {
        notificationService.markRead(id, principal.id(), principal.tenantId());
        return ApiResponse.ok(null, "通知已读");
    }

    @PostMapping("/read-all")
    public ApiResponse<Integer> markAllRead(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(notificationService.markAllRead(principal.id(), principal.tenantId()), "通知已全部读完");
    }

    @PostMapping("/scan")
    public ApiResponse<Integer> scan(@AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以执行 SLA 扫描");
        }
        return ApiResponse.ok(notificationService.scanTenant(principal.tenantId()), "SLA 扫描完成");
    }
}