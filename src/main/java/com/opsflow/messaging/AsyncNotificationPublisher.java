package com.opsflow.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AsyncNotificationPublisher {
    private static final Logger log = LoggerFactory.getLogger(AsyncNotificationPublisher.class);

    private final AsyncMessageOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public AsyncNotificationPublisher(AsyncMessageOutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean enqueue(Long tenantId, Long recipientId, String type,
                           String title, String content, String dedupeKey) {
        return enqueue(tenantId, recipientId, null, type, title, content, dedupeKey,
                "NOTIFICATION", recipientId);
    }

    @Transactional
    public boolean enqueue(Long tenantId, Long recipientId, Long ticketId, String type,
                           String title, String content, String dedupeKey,
                           String aggregateType, Long aggregateId) {
        require(tenantId, "tenantId");
        require(recipientId, "recipientId");
        requireText(type, "type", 32);
        requireText(title, "title", 160);
        requireText(content, "content", 500);
        requireText(dedupeKey, "dedupeKey", 160);

        String messageId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        AsyncNotificationMessage message = new AsyncNotificationMessage(messageId, tenantId, recipientId,
                ticketId, type, title, content, dedupeKey, now.toString());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to serialize asynchronous notification", ex);
        }

        AsyncMessageOutboxEvent event = new AsyncMessageOutboxEvent();
        event.setTenantId(tenantId);
        event.setRecipientId(recipientId);
        event.setMessageId(messageId);
        event.setEventType("NOTIFICATION_REQUESTED");
        event.setNotificationType(type);
        event.setTitle(title);
        event.setRoutingKey(MessagingTopology.NOTIFICATION_ROUTING_KEY);
        event.setAggregateType(blankToNull(aggregateType));
        event.setAggregateId(aggregateId);
        event.setDedupeKey(dedupeKey);
        event.setPayloadJson(payload);
        event.setStatus(AsyncMessageOutboxStatus.PENDING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        try {
            outboxMapper.insert(event);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Asynchronous notification already queued for tenant {}, recipient {} and key {}",
                    tenantId, recipientId, dedupeKey);
            return false;
        }
    }

    private void require(Object value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        if (value.length() > maxLength) throw new IllegalArgumentException(field + " exceeds " + maxLength);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
