package com.opsflow.auth;

import com.opsflow.common.ApiResponse;
import com.opsflow.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request.username(), request.password(), request.displayName()), "注册成功");
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()), "登录成功");
    }

    @GetMapping("/staff")
    public ApiResponse<List<StaffSummary>> staff(@AuthenticationPrincipal AuthPrincipal principal) {
        if (!principal.hasAnyRole("AGENT", "ADMIN")) {
            throw new BusinessException("只有客服或管理员可以查看处理人目录");
        }
        return ApiResponse.ok(authService.staffMembers(principal.tenantId()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        authService.invalidateTokens(principal.id());
        return ApiResponse.ok(null, "已退出登录");
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        authService.changePassword(principal.id(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok(null, "密码已更新，请重新登录");
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }
    public record RegisterRequest(@NotBlank @Size(min = 4, max = 64) String username,
                                  @NotBlank @Size(min = 8, max = 72) String password,
                                  @NotBlank @Size(max = 64) String displayName) { }
    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) { }
}
