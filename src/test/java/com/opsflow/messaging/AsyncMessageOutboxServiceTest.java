package com.opsflow.messaging;

import com.opsflow.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncMessageOutboxServiceTest {
    @Mock private AsyncMessageOutboxMapper outboxMapper;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private AuditLogService auditLogService;
    @Mock private AsyncMessagingMetrics metrics;
    @Mock private PlatformTransactionManager transactionManager;

    private AsyncMessageOutboxService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        lenient().when(outboxMapper.selectCount(any())).thenReturn(0L);
        service = new AsyncMessageOutboxService(outboxMapper, rabbitTemplate, auditLogService,
                metrics, transactionManager, Duration.ofSeconds(1));
    }

    @Test
    void marksMessagePublishedOnlyAfterBrokerConfirm() {
        AsyncMessageOutboxEvent event = event(AsyncMessageOutboxStatus.PENDING);
        when(outboxMapper.selectList(any())).thenReturn(List.of(event));
        when(outboxMapper.selectForUpdate(31L)).thenReturn(event);
        confirmPublish(true, null);

        int published = service.dispatchPending(20);

        assertThat(published).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(AsyncMessageOutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(metrics).published();
    }

    @Test
    void brokerNackMovesMessageToRetryableFailure() {
        AsyncMessageOutboxEvent event = event(AsyncMessageOutboxStatus.PENDING);
        when(outboxMapper.selectList(any())).thenReturn(List.of(event));
        when(outboxMapper.selectForUpdate(31L)).thenReturn(event);
        confirmPublish(false, "broker unavailable");

        int published = service.dispatchPending(20);

        assertThat(published).isZero();
        assertThat(event.getStatus()).isEqualTo(AsyncMessageOutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker unavailable");
    }

    @Test
    void administratorCanRequeueDeadMessage() {
        AsyncMessageOutboxEvent event = event(AsyncMessageOutboxStatus.DEAD);
        event.setAttemptCount(8);
        when(outboxMapper.selectForUpdateInTenant(31L, 3L)).thenReturn(event);

        AsyncMessageOutboxEvent result = service.retry(31L, 3L, 7L);

        assertThat(result.getStatus()).isEqualTo(AsyncMessageOutboxStatus.PENDING);
        assertThat(result.getAttemptCount()).isZero();
        verify(auditLogService).record(eq(3L), eq(7L), eq("ASYNC_MESSAGE"), eq(31L),
                eq("ASYNC_MESSAGE_RETRY"), eq(null), eq(result), anyString());
    }

    @Test
    void administratorCanRequeueMultipleFailedMessages() {
        AsyncMessageOutboxEvent first = event(AsyncMessageOutboxStatus.FAILED);
        AsyncMessageOutboxEvent second = event(AsyncMessageOutboxStatus.DEAD);
        second.setId(32L);
        when(outboxMapper.selectForUpdateInTenant(31L, 3L)).thenReturn(first);
        when(outboxMapper.selectForUpdateInTenant(32L, 3L)).thenReturn(second);

        int retried = service.retryBatch(List.of(31L, 32L, 32L), 3L, 7L);

        assertThat(retried).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(AsyncMessageOutboxStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(AsyncMessageOutboxStatus.PENDING);
    }

    private void confirmPublish(boolean acknowledged, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(acknowledged, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(MessagingTopology.EXCHANGE), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private AsyncMessageOutboxEvent event(AsyncMessageOutboxStatus status) {
        AsyncMessageOutboxEvent event = new AsyncMessageOutboxEvent();
        event.setId(31L);
        event.setTenantId(3L);
        event.setRecipientId(7L);
        event.setMessageId("2a00ed6e-615d-4ee8-b34c-4c42cb661cab");
        event.setEventType("NOTIFICATION_REQUESTED");
        event.setNotificationType("REPORT_READY");
        event.setTitle("运营报表");
        event.setRoutingKey(MessagingTopology.NOTIFICATION_ROUTING_KEY);
        event.setAggregateType("REPORT_SUBSCRIPTION");
        event.setAggregateId(9L);
        event.setDedupeKey("REPORT:9");
        event.setPayloadJson("{}");
        event.setStatus(status);
        event.setAttemptCount(0);
        event.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        event.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        event.setUpdatedAt(LocalDateTime.now().minusMinutes(2));
        return event;
    }
}
