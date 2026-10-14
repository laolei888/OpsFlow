package com.opsflow.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SensitiveDataSanitizer {
    private static final String MASK = "***";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwordhash", "currentpassword", "newpassword",
            "secret", "token", "accesstoken", "refreshtoken", "authorization",
            "apikey", "ingestkey", "signature");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|secret|token|authorization|api[_-]?key)(\\s*[:=]\\s*)([^,;\\s}]+)");

    private final ObjectMapper objectMapper;

    public SensitiveDataSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode sanitize(Object value) {
        JsonNode root = objectMapper.valueToTree(value);
        scrub(root);
        return root;
    }

    public String sanitizeText(String value) {
        if (value == null) return null;
        String withoutBearer = BEARER_TOKEN.matcher(value).replaceAll("Bearer " + MASK);
        return KEY_VALUE_SECRET.matcher(withoutBearer).replaceAll("$1$2" + MASK);
    }

    private void scrub(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    objectNode.put(field.getKey(), MASK);
                } else {
                    scrub(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::scrub);
        }
    }

    private boolean isSensitive(String key) {
        if (key == null) return false;
        String normalized = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized)
                || normalized.endsWith("password")
                || normalized.endsWith("passwordhash")
                || normalized.endsWith("secret")
                || normalized.endsWith("token")
                || normalized.endsWith("apikey")
                || normalized.endsWith("signature");
    }
}
