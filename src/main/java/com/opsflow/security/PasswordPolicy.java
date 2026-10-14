package com.opsflow.security;

import com.opsflow.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class PasswordPolicy {
    private static final int MIN_LENGTH = 10;
    private static final int MAX_LENGTH = 72;
    private static final Set<String> BLOCKED_PASSWORDS = Set.of(
            "1234567890", "password123", "qwerty12345", "admin123456",
            "opsflow123", "1111111111", "123456789a");

    public void validate(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new BusinessException("密码长度必须为 10-72 位");
        }
        String normalized = password.toLowerCase(Locale.ROOT);
        if (BLOCKED_PASSWORDS.contains(normalized)) {
            throw new BusinessException("密码过于常见，请使用更难猜测的密码或长口令");
        }
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (normalizedUsername.length() >= 4 && normalized.contains(normalizedUsername)) {
            throw new BusinessException("密码不能包含用户名");
        }
    }
}
