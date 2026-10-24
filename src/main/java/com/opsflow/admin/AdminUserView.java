package com.opsflow.admin;

import com.opsflow.auth.AuthSource;
import com.opsflow.auth.UserStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AdminUserView(Long id, String username, String displayName,
                            Long tenantId, UserStatus status, List<String> roles,
                            AuthSource authSource, String email, LocalDateTime lastLoginAt,
                            LocalDateTime createdAt) {
}
