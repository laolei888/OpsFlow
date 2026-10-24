package com.opsflow.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Arrays;
import java.util.Collection;

public record AuthPrincipal(Long id, Long tenantId, String username,
                            Collection<? extends GrantedAuthority> authorities) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return null; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }

    public boolean hasAnyRole(String... roles) {
        return Arrays.stream(roles)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .anyMatch(expected -> authorities.stream()
                        .anyMatch(authority -> expected.equals(authority.getAuthority())));
    }

    public boolean hasPermission(String permission) {
        String expected = permission.startsWith("PERM_") ? permission : "PERM_" + permission;
        return authorities.stream().anyMatch(authority -> expected.equals(authority.getAuthority()));
    }
}
