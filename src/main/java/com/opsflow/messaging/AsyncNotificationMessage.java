package com.opsflow.messaging;

public record AsyncNotificationMessage(
        String messageId,
        Long tenantId,
        Long recipientId,
        Long ticketId,
        String type,
        String title,
        String content,
        String dedupeKey,
        String occurredAt) {
}
