package com.opsflow.auth;

import java.util.List;

public record AuthResponse(String token, long expiresInSeconds, Long userId,
                           String username, String displayName, Long tenantId,
                           List<String> roles) { }
