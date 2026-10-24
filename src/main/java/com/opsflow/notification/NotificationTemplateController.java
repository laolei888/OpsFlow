package com.opsflow.notification;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/admin/notification-templates")
@RequiredArgsConstructor
public class NotificationTemplateController {
    private final NotificationTemplateService templateService;

    @GetMapping
    public ApiResponse<List<NotificationTemplateService.TemplateView>> list(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) NotificationChannel channel,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.list(principal.tenantId(), eventType, channel));
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationTemplateService.TemplateDetailView> detail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.detail(id, principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<NotificationTemplateService.TemplateDetailView> create(
            @Valid @RequestBody CreateRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.create(request.command(), principal.tenantId(), principal.id()),
                "通知模板草稿已创建");
    }

    @PutMapping("/{id}")
    public ApiResponse<NotificationTemplateService.TemplateView> update(
            @PathVariable Long id, @Valid @RequestBody MetadataRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.update(id, request.command(), principal.tenantId(), principal.id()),
                "通知模板信息已更新");
    }

    @PostMapping("/{id}/versions")
    public ApiResponse<NotificationTemplateService.VersionView> createVersion(
            @PathVariable Long id, @Valid @RequestBody VersionRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.createVersion(id, request.command(), principal.tenantId(), principal.id()),
                "模板草稿版本已创建");
    }

    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<NotificationTemplateService.TemplateDetailView> publish(
            @PathVariable Long versionId, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.publish(versionId, principal.tenantId(), principal.id()),
                "通知模板版本已发布");
    }

    @GetMapping("/versions/{versionId}/preview")
    public ApiResponse<NotificationTemplateService.PreviewView> preview(
            @PathVariable Long versionId, @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.preview(versionId, principal.tenantId()));
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<NotificationTemplateService.TemplateView> toggle(
            @PathVariable Long id, @RequestParam boolean enabled,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(templateService.setEnabled(id, enabled, principal.tenantId(), principal.id()),
                enabled ? "通知模板已启用" : "通知模板已停用");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理通知模板");
        }
    }

    public record CreateRequest(@NotBlank @Size(max = 120) String name,
                                @Size(max = 500) String description,
                                @NotBlank @Size(max = 64) String eventType,
                                @NotNull NotificationChannel channel,
                                @NotBlank @Size(max = 300) String titleTemplate,
                                @NotBlank @Size(max = 20000) String contentTemplate,
                                @Size(max = 500) String changeNote) {
        NotificationTemplateService.CreateCommand command() {
            return new NotificationTemplateService.CreateCommand(name, description, eventType, channel,
                    titleTemplate, contentTemplate, changeNote);
        }
    }

    public record MetadataRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description) {
        NotificationTemplateService.MetadataCommand command() {
            return new NotificationTemplateService.MetadataCommand(name, description);
        }
    }

    public record VersionRequest(@NotBlank @Size(max = 300) String titleTemplate,
                                 @NotBlank @Size(max = 20000) String contentTemplate,
                                 @Size(max = 500) String changeNote) {
        NotificationTemplateService.VersionCommand command() {
            return new NotificationTemplateService.VersionCommand(titleTemplate, contentTemplate, changeNote);
        }
    }
}
