package com.opsflow.identity;

import com.opsflow.auth.AuthResponse;
import com.opsflow.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class OidcAuthController {
    private final OidcProviderService providerService;
    private final OidcLoginExchangeService exchangeService;

    @GetMapping("/providers")
    public ApiResponse<List<OidcProviderService.PublicOidcProvider>> providers() {
        return ApiResponse.ok(providerService.publicProviders());
    }

    @PostMapping("/exchange")
    public ApiResponse<AuthResponse> exchange(@Valid @RequestBody ExchangeRequest request) {
        return ApiResponse.ok(exchangeService.consume(request.code()), "单点登录成功");
    }

    public record ExchangeRequest(@NotBlank String code) { }
}
