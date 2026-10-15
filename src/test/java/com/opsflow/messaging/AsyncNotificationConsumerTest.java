package com.opsflow.messaging;

import com.opsflow.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncNotificationConsumerTest {
    @Mock private ObjectMapper objectMapper;
    @Mock private NotificationService notificationService;
    @Mock private AsyncMessagingMetrics metrics;

    @Test
    void consumesNotificationWithDatabaseIdempotency() {
        AsyncNotificationMessage message = message();
        when(objectMapper.readValue("{}", AsyncNotificationMessage.class)).thenReturn(message);
        when(notificationService.notifyIfAbsent(3L, 7L, null, "REPORT_READY",
                "运营报表", "新增 5", "REPORT:9")).thenReturn(false);
        AsyncNotificationConsumer consumer = new AsyncNotificationConsumer(
                objectMapper, notificationService, metrics);

        consumer.consume("{}".getBytes(StandardCharsets.UTF_8));

        verify(notificationService).notifyIfAbsent(3L, 7L, null, "REPORT_READY",
                "运营报表", "新增 5", "REPORT:9");
        verify(metrics).consumed(false);
    }

    @Test
    void invalidPayloadFailsForRetryAndDeadLettering() {
        when(objectMapper.readValue("{}", AsyncNotificationMessage.class))
                .thenReturn(new AsyncNotificationMessage(null, 3L, 7L, null,
                        "REPORT_READY", "运营报表", "新增 5", "REPORT:9", "2026-10-15T09:00:00"));
        AsyncNotificationConsumer consumer = new AsyncNotificationConsumer(
                objectMapper, notificationService, metrics);

        assertThatThrownBy(() -> consumer.consume("{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to consume");
        verify(metrics).consumeFailed();
    }

    private AsyncNotificationMessage message() {
        return new AsyncNotificationMessage("2a00ed6e-615d-4ee8-b34c-4c42cb661cab",
                3L, 7L, null, "REPORT_READY", "运营报表", "新增 5", "REPORT:9",
                "2026-10-15T09:00:00");
    }
}
