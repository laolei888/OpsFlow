package com.opsflow.identity;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class PermissionAdminController {
    private final PermissionService permissionService;

    @GetMapping("/permissions")
    public ApiResponse<List<AccessPermission>> catalog(@AuthenticationPrincipal AuthPrincipal principal) {
        requireManage(principal);
        return ApiResponse.ok(permissionService.catalog());
    }

    @GetMapping("/users/{userId}/permissions")
    public ApiResponse<PermissionService.UserPermissionView> userPermissions(
            @PathVariable Long userId, @AuthenticationPrincipal AuthPrincipal principal) {
        requireManage(principal);
        return ApiResponse.ok(permissionService.view(userId, principal.tenantId()));
    }

    @PutMapping("/users/{userId}/permissions")
    public ApiResponse<PermissionService.UserPermissionView> replace(
            @PathVariable Long userId, @Valid @RequestBody ReplacePermissionRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireManage(principal);
        List<PermissionService.PermissionOverrideCommand> commands = request.overrides().stream()
                .map(item -> new PermissionService.PermissionOverrideCommand(
                        item.code(), item.effect(), item.reason()))
                .toList();
        return ApiResponse.ok(permissionService.replaceOverrides(userId, principal.tenantId(),
                principal.id(), commands), "权限策略已更新，目标账号需要重新登录");
    }

    private void requireManage(AuthPrincipal principal) {
        if (principal == null || !principal.hasPermission("PERMISSION_MANAGE")) {
            throw new BusinessException("当前账号没有权限策略管理能力");
        }
    }

    public record ReplacePermissionRequest(@NotNull @Size(max = 100) List<@Valid PermissionOverrideRequest> overrides) { }
    public record PermissionOverrideRequest(@NotBlank String code,
                                            @NotNull PermissionOverrideEffect effect,
                                            @Size(max = 300) String reason) { }
}
