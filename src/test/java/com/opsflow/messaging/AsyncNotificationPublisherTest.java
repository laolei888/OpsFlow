package com.opsflow.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncNotificationPublisherTest {
    @Mock private AsyncMessageOutboxMapper outboxMapper;
    @Mock private ObjectMapper objectMapper;

    @Test
    void storesNotificationInTransactionalOutbox() {
        when(objectMapper.writeValueAsString(any(AsyncNotificationMessage.class)))
                .thenReturn("{\"type\":\"REPORT_READY\"}");
        AsyncNotificationPublisher publisher = new AsyncNotificationPublisher(outboxMapper, objectMapper);

        boolean queued = publisher.enqueue(3L, 7L, null, "REPORT_READY", "运营报表", "新增 5",
                "REPORT:9:2026-10-15", "REPORT_SUBSCRIPTION", 9L);

        assertThat(queued).isTrue();
        ArgumentCaptor<AsyncMessageOutboxEvent> captor =
                ArgumentCaptor.forClass(AsyncMessageOutboxEvent.class);
        verify(outboxMapper).insert(captor.capture());
        AsyncMessageOutboxEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(3L);
        assertThat(event.getRecipientId()).isEqualTo(7L);
        assertThat(event.getStatus()).isEqualTo(AsyncMessageOutboxStatus.PENDING);
        assertThat(event.getNotificationType()).isEqualTo("REPORT_READY");
        assertThat(event.getTitle()).isEqualTo("运营报表");
        assertThat(event.getRoutingKey()).isEqualTo(MessagingTopology.NOTIFICATION_ROUTING_KEY);
        assertThat(event.getMessageId()).hasSize(36);
        assertThat(event.getNextAttemptAt()).isNotNull();
    }

    @Test
    void duplicateDedupeKeyIsIgnored() {
        when(objectMapper.writeValueAsString(any(AsyncNotificationMessage.class))).thenReturn("{}");
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(outboxMapper).insert(any(AsyncMessageOutboxEvent.class));
        AsyncNotificationPublisher publisher = new AsyncNotificationPublisher(outboxMapper, objectMapper);

        boolean queued = publisher.enqueue(3L, 7L, "REPORT_READY", "运营报表", "内容", "REPORT:9");

        assertThat(queued).isFalse();
    }
}
