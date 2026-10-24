package com.opsflow.identity;

import com.opsflow.auth.User;
import com.opsflow.security.OpsFlowSecurityProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private final OidcAccountService accountService;
    private final OidcLoginExchangeService exchangeService;
    private final OpsFlowSecurityProperties properties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(token.getPrincipal() instanceof OidcUser oidcUser)) {
            clearTransientSession(request);
            response.sendRedirect(callback("unsupported_identity"));
            return;
        }
        try {
            User user = accountService.authenticate(token.getAuthorizedClientRegistrationId(), oidcUser.getClaims());
            String exchangeCode = exchangeService.issue(user);
            String target = UriComponentsBuilder.fromPath(callbackPath())
                    .queryParam("code", exchangeCode).build().encode().toUriString();
            clearTransientSession(request);
            response.sendRedirect(target);
        } catch (RuntimeException ex) {
            clearTransientSession(request);
            response.sendRedirect(callback("account_mapping_failed"));
        }
    }

    private String callback(String error) {
        return UriComponentsBuilder.fromPath(callbackPath())
                .queryParam("error", error).build().encode().toUriString();
    }

    private String callbackPath() {
        String path = properties.getOidc().getFrontendCallbackPath();
        return path != null && path.startsWith("/") && !path.startsWith("//") ? path : "/sso/callback";
    }

    private void clearTransientSession(HttpServletRequest request) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
    }
}
