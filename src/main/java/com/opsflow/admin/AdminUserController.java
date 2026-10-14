package com.opsflow.admin;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.auth.UserStatus;
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
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminUserService adminUserService;

    @GetMapping
    public ApiResponse<List<AdminUserView>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(adminUserService.list(principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<AdminUserView> create(@Valid @RequestBody CreateUserRequest request,
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(adminUserService.create(request.username(), request.password(),
                request.displayName(), request.role(), principal.tenantId(), principal.id()), "用户创建成功");
    }

    @PatchMapping("/{userId}/status")
    public ApiResponse<AdminUserView> changeStatus(@PathVariable Long userId,
                                                   @Valid @RequestBody StatusRequest request,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(adminUserService.changeStatus(userId, request.status(),
                principal.id(), principal.tenantId()), "用户状态已更新");
    }

    @PutMapping("/{userId}/role")
    public ApiResponse<AdminUserView> changeRole(@PathVariable Long userId,
                                                 @Valid @RequestBody RoleRequest request,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return ApiResponse.ok(adminUserService.changeRole(userId, request.role(),
                principal.id(), principal.tenantId()), "用户角色已更新");
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.hasAnyRole("ADMIN")) {
            throw new BusinessException("只有管理员可以管理用户");
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 4, max = 64) String username,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank String role) {
    }

    public record StatusRequest(@NotNull UserStatus status) { }

    public record RoleRequest(@NotBlank String role) { }
}
