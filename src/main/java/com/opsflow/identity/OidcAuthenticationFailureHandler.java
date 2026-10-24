package com.opsflow.identity;

import com.opsflow.security.OpsFlowSecurityProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OidcAuthenticationFailureHandler implements AuthenticationFailureHandler {
    private final OpsFlowSecurityProperties properties;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        String path = properties.getOidc().getFrontendCallbackPath();
        if (path == null || !path.startsWith("/") || path.startsWith("//")) path = "/sso/callback";
        response.sendRedirect(UriComponentsBuilder.fromPath(path)
                .queryParam("error", "identity_provider_failed").build().encode().toUriString());
    }
}
