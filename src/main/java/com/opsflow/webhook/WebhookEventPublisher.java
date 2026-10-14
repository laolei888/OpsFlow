package com.opsflow.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.audit.AuditLog;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebhookEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventPublisher.class);

    private final WebhookOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    /**
     * Persists the integration event in the caller's database transaction. Endpoint matching,
     * signing and delivery expansion happen asynchronously after the transaction commits.
     */
    public void publish(AuditLog audit) {
        if (audit == null || audit.getTenantId() == null || audit.getId() == null || audit.getAction() == null) return;
        String eventType = audit.getAction().trim().toUpperCase(Locale.ROOT);
        String eventId = eventId(audit.getId());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payload(audit, eventId, eventType));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize webhook outbox event", ex);
        }

        LocalDateTime now = LocalDateTime.now();
        WebhookOutboxEvent event = new WebhookOutboxEvent();
        event.setTenantId(audit.getTenantId());
        event.setAuditLogId(audit.getId());
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setActorId(audit.getActorId());
        event.setResourceType(audit.getResourceType());
        event.setResourceId(audit.getResourceId());
        event.setPayloadJson(payload);
        event.setStatus(WebhookOutboxStatus.PENDING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        try {
            outboxMapper.insert(event);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Webhook outbox event already exists for audit {}", audit.getId());
        }
    }

    public static String sign(String secret, String payload) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Webhook secret is blank");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return hex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate webhook signature", ex);
        }
    }

    private Map<String, Object> payload(AuditLog audit, String eventId, String eventType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("eventType", eventType);
        result.put("occurredAt", audit.getCreatedAt() == null ? null : audit.getCreatedAt().toString());
        result.put("tenantId", audit.getTenantId());
        result.put("actorId", audit.getActorId());
        result.put("resourceType", audit.getResourceType());
        result.put("resourceId", audit.getResourceId());
        result.put("detail", audit.getDetail());
        result.put("before", parseSnapshot(audit.getBeforeValue()));
        result.put("after", parseSnapshot(audit.getAfterValue()));
        return result;
    }

    private Object parseSnapshot(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(value);
            return node == null ? value : node;
        } catch (JsonProcessingException ex) {
            return value;
        }
    }

    private String eventId(Long auditId) {
        return hex(digest("audit:" + auditId));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
