package com.opsflow.messaging;

import com.opsflow.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Component
public class AsyncNotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(AsyncNotificationConsumer.class);

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final AsyncMessagingMetrics metrics;

    public AsyncNotificationConsumer(ObjectMapper objectMapper,
                                     NotificationService notificationService,
                                     AsyncMessagingMetrics metrics) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.metrics = metrics;
    }

    @RabbitListener(queues = MessagingTopology.NOTIFICATION_QUEUE,
            concurrency = "${opsflow.messaging.consumer-concurrency:2}")
    public void consume(byte[] body) {
        try {
            String payload = new String(body, StandardCharsets.UTF_8);
            AsyncNotificationMessage message = objectMapper.readValue(payload, AsyncNotificationMessage.class);
            validate(message);
            boolean created = notificationService.notifyIfAbsent(message.tenantId(), message.recipientId(),
                    message.ticketId(), message.type(), message.title(), message.content(), message.dedupeKey());
            metrics.consumed(created);
            if (!created) {
                log.debug("Ignored duplicate asynchronous notification {}", message.messageId());
            }
        } catch (RuntimeException ex) {
            metrics.consumeFailed();
            throw new IllegalStateException("Unable to consume asynchronous notification", ex);
        }
    }

    private void validate(AsyncNotificationMessage message) {
        if (message == null || message.messageId() == null || message.messageId().isBlank()
                || message.tenantId() == null || message.recipientId() == null
                || message.type() == null || message.type().isBlank()
                || message.title() == null || message.title().isBlank()
                || message.content() == null || message.content().isBlank()
                || message.dedupeKey() == null || message.dedupeKey().isBlank()) {
            throw new IllegalArgumentException("Asynchronous notification payload is incomplete");
        }
    }
}
