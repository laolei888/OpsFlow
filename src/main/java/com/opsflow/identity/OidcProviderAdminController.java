package com.opsflow.identity;

import com.opsflow.auth.AuthPrincipal;
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
@RequestMapping("/api/v1/admin/identity/providers")
@RequiredArgsConstructor
public class OidcProviderAdminController {
    private final OidcProviderService service;

    @GetMapping
    public ApiResponse<List<OidcProviderService.OidcProviderView>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireManage(principal);
        return ApiResponse.ok(service.list(principal.tenantId()));
    }

    @PostMapping
    public ApiResponse<OidcProviderService.OidcProviderView> create(
            @Valid @RequestBody ProviderRequest request, @AuthenticationPrincipal AuthPrincipal principal) {
        requireManage(principal);
        return ApiResponse.ok(service.create(principal.tenantId(), principal.id(), request.command()),
                "企业身份提供商已创建");
    }

    @PutMapping("/{providerId}")
    public ApiResponse<OidcProviderService.OidcProviderView> update(
            @PathVariable Long providerId, @Valid @RequestBody ProviderRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireManage(principal);
        return ApiResponse.ok(service.update(providerId, principal.tenantId(), principal.id(), request.command()),
                "企业身份提供商已更新");
    }

    @PatchMapping("/{providerId}/enabled")
    public ApiResponse<OidcProviderService.OidcProviderView> enabled(
            @PathVariable Long providerId, @RequestBody EnabledRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireManage(principal);
        return ApiResponse.ok(service.setEnabled(providerId, principal.tenantId(), principal.id(), request.enabled()),
                request.enabled() ? "企业身份提供商已启用" : "企业身份提供商已停用");
    }

    private void requireManage(AuthPrincipal principal) {
        if (principal == null || !principal.hasPermission("IDENTITY_PROVIDER_MANAGE")) {
            throw new BusinessException("当前账号没有企业身份提供商管理能力");
        }
    }

    public record ProviderRequest(@NotBlank @Size(max = 64) String registrationId,
                                  @NotBlank @Size(max = 120) String displayName,
                                  Boolean enabled, Boolean autoProvision,
                                  @NotBlank String defaultRole,
                                  @Size(max = 1000) String allowedEmailDomains,
                                  @Size(max = 191) String requiredGroup,
                                  @Size(max = 64) String usernameClaim,
                                  @Size(max = 64) String displayNameClaim,
                                  @Size(max = 64) String emailClaim,
                                  @Size(max = 64) String groupsClaim) {
        OidcProviderService.OidcProviderCommand command() {
            return new OidcProviderService.OidcProviderCommand(registrationId, displayName, enabled,
                    autoProvision, defaultRole, allowedEmailDomains, requiredGroup, usernameClaim,
                    displayNameClaim, emailClaim, groupsClaim);
        }
    }
    public record EnabledRequest(boolean enabled) { }
}
