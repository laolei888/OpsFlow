package com.opsflow.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SensitiveDataSanitizerTest {
    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new ObjectMapper());

    @Test
    void masksNestedSecretsWithoutRemovingOperationalFields() {
        JsonNode sanitized = sanitizer.sanitize(Map.of(
                "name", "monitoring-hook",
                "credentials", Map.of("apiKey", "top-secret", "token", "jwt-value",
                        "database_password", "db-secret")));

        assertEquals("monitoring-hook", sanitized.get("name").asText());
        assertEquals("***", sanitized.get("credentials").get("apiKey").asText());
        assertEquals("***", sanitized.get("credentials").get("token").asText());
        assertEquals("***", sanitized.get("credentials").get("database_password").asText());
    }

    @Test
    void masksBearerAndKeyValueSecretsInFreeText() {
        String sanitized = sanitizer.sanitizeText("Authorization=Bearer abc.def password=hunter2");

        assertFalse(sanitized.contains("abc.def"));
        assertFalse(sanitized.contains("hunter2"));
    }
}
