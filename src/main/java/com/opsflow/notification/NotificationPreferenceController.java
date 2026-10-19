package com.opsflow.notification;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferenceController {
    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public ApiResponse<NotificationPreferenceService.PreferenceView> get(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(preferenceService.get(principal.tenantId(), principal.id()));
    }

    @PutMapping
    public ApiResponse<NotificationPreferenceService.PreferenceView> update(
            @Valid @RequestBody PreferenceRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(preferenceService.update(request.command(), principal.tenantId(), principal.id()),
                "通知偏好已更新");
    }

    public record PreferenceRequest(@Email @Size(max = 254) String emailAddress,
                                    @Size(max = 1000) String webhookUrl,
                                    @Size(max = 200) String webhookSecret,
                                    @Size(max = 50) List<RuleRequest> rules) {
        NotificationPreferenceService.PreferenceCommand command() {
            return new NotificationPreferenceService.PreferenceCommand(emailAddress, webhookUrl, webhookSecret,
                    rules == null ? List.of() : rules.stream().map(RuleRequest::command).toList());
        }
    }

    public record RuleRequest(@Size(max = 64) String eventType, boolean inAppEnabled,
                              boolean emailEnabled, boolean webhookEnabled) {
        NotificationPreferenceService.RuleCommand command() {
            return new NotificationPreferenceService.RuleCommand(eventType, inAppEnabled, emailEnabled, webhookEnabled);
        }
    }
}
