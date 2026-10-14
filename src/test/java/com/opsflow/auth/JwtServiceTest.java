package com.opsflow.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {
    @Test
    void rejectsMissingOrShortSigningKeys() {
        assertThrows(IllegalStateException.class, () -> new JwtService("", Duration.ofHours(2)));
        assertThrows(IllegalStateException.class, () -> new JwtService("short-secret", Duration.ofHours(2)));
    }

    @Test
    void generatesSignedTokenWithIdentityClaims() {
        JwtService service = new JwtService("0123456789abcdef0123456789abcdef", Duration.ofHours(2));
        User user = new User();
        user.setId(7L);
        user.setUsername("agent");
        user.setTenantId(3L);
        user.setTokenVersion(2);

        var claims = service.parse(service.generate(user));

        assertEquals("agent", claims.getSubject());
        assertEquals(7, claims.get("uid", Number.class).intValue());
        assertEquals(3, claims.get("tenantId", Number.class).intValue());
        assertEquals(2, claims.get("tokenVersion", Number.class).intValue());
    }
}
