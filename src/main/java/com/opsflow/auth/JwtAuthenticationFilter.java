package com.opsflow.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.opsflow.identity.PermissionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final AuthService authService;
    private final PermissionService permissionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                User user = authService.findByUsername(claims.getSubject());
                Number tokenVersion = claims.get("tokenVersion", Number.class);
                if (user.getStatus() == UserStatus.ACTIVE && tokenVersion != null
                        && tokenVersion.intValue() == user.getTokenVersion()) {
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authServiceRoles(user).stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .forEach(authorities::add);
                    permissionService.effectiveCodes(user.getId(), user.getTenantId()).stream()
                            .map(code -> new SimpleGrantedAuthority("PERM_" + code))
                            .forEach(authorities::add);
                    AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getTenantId(), user.getUsername(), authorities);
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, authorities));
                }
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private List<String> authServiceRoles(User user) {
        return authService.rolesOf(user.getId());
    }
}
