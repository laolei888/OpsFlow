package com.opsflow.messaging;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterMessageServiceTest {
    @Mock private DeadLetterMessageMapper deadLetterMapper;
    @Mock private AsyncMessageOutboxMapper outboxMapper;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private AuditLogService auditLogService;
    @Mock private AsyncMessagingMetrics metrics;
    @Mock private PlatformTransactionManager transactionManager;

    private DeadLetterMessageService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        lenient().when(deadLetterMapper.selectCount(any())).thenReturn(0L);
        service = new DeadLetterMessageService(deadLetterMapper, outboxMapper, rabbitTemplate,
                auditLogService, metrics, new ObjectMapper(), transactionManager, Duration.ofSeconds(1));
    }

    @Test
    void archivesBrokerDeadLetterWithTenantAndFailureEvidence() {
        AsyncMessageOutboxEvent outbox = new AsyncMessageOutboxEvent();
        outbox.setId(77L);
        when(outboxMapper.selectOne(any())).thenReturn(outbox);
        Message message = deadLetterMessage("f52a2690-58a7-4b94-a026-2f0dc871938d");

        service.quarantine(message);

        ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(deadLetterMapper).insert(captor.capture());
        DeadLetterMessage archived = captor.getValue();
        assertThat(archived.getTenantId()).isEqualTo(3L);
        assertThat(archived.getOutboxEventId()).isEqualTo(77L);
        assertThat(archived.getMessageId()).isEqualTo("f52a2690-58a7-4b94-a026-2f0dc871938d");
        assertThat(archived.getNotificationType()).isEqualTo("REPORT_READY");
        assertThat(archived.getOriginalRoutingKey()).isEqualTo(MessagingTopology.NOTIFICATION_ROUTING_KEY);
        assertThat(archived.getDeadLetterReason()).isEqualTo("rejected");
        assertThat(archived.getDeathCount()).isEqualTo(2L);
        assertThat(archived.getStatus()).isEqualTo(DeadLetterStatus.QUARANTINED);
        assertThat(archived.getHeadersJson()).contains("x-death");
        verify(metrics).deadLetterQuarantined();
    }

    @Test
    void requeueRequiresExactMessageIdConfirmation() {
        DeadLetterMessage message = quarantinedMessage();
        when(deadLetterMapper.selectForUpdateInTenant(31L, 3L)).thenReturn(message);

        assertThatThrownBy(() -> service.requeue(31L, 3L, 7L, "wrong-id", "故障已经修复"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("二次确认不匹配");

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void requeuesOnlyAfterBrokerConfirmationAndRecordsAudit() {
        DeadLetterMessage message = quarantinedMessage();
        when(deadLetterMapper.selectForUpdateInTenant(31L, 3L)).thenReturn(message);
        confirmPublish(true, null);

        DeadLetterMessage result = service.requeue(31L, 3L, 7L, message.getMessageId(), "数据约束已经修复");

        assertThat(result.getStatus()).isEqualTo(DeadLetterStatus.REQUEUED);
        assertThat(result.getRequeueCount()).isEqualTo(1);
        assertThat(result.getResolutionNote()).isEqualTo("数据约束已经修复");
        assertThat(result.getLastRequeuedAt()).isNotNull();
        verify(auditLogService).record(eq(3L), eq(7L), eq("DEAD_LETTER"), eq(31L),
                eq("DEAD_LETTER_REQUEUED"), eq(null), eq(result), anyString());
        verify(metrics).deadLetterRequeued();
    }

    @Test
    void brokerRejectionRestoresMessageToQuarantine() {
        DeadLetterMessage message = quarantinedMessage();
        when(deadLetterMapper.selectForUpdateInTenant(31L, 3L)).thenReturn(message);
        confirmPublish(false, "resource alarm");

        assertThatThrownBy(() -> service.requeue(31L, 3L, 7L, message.getMessageId(), "Broker 容量已经恢复"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RabbitMQ 拒绝");

        assertThat(message.getStatus()).isEqualTo(DeadLetterStatus.QUARANTINED);
        assertThat(message.getLastError()).contains("resource alarm");
    }

    @Test
    void discardsMessageWithReasonAndExportsOnlyTenantSelection() {
        DeadLetterMessage message = quarantinedMessage();
        message.setTitle("=SUM(A1:A2)");
        when(deadLetterMapper.selectForUpdateInTenant(31L, 3L)).thenReturn(message);

        DeadLetterMessage discarded = service.discard(31L, 3L, 7L, message.getMessageId(), "业务方确认无需补发");

        assertThat(discarded.getStatus()).isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(discarded.getResolvedAt()).isNotNull();
        when(deadLetterMapper.selectList(any())).thenReturn(List.of(discarded));
        String csv = new String(service.exportCsv(3L, 7L, List.of(31L)), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff");
        assertThat(csv).contains("\"'=SUM(A1:A2)\"");
        assertThat(csv).contains("业务方确认无需补发");
        verify(metrics).deadLetterDiscarded();
    }

    private Message deadLetterMessage(String messageId) {
        String payload = """
                {"messageId":"%s","tenantId":3,"recipientId":7,"ticketId":null,
                 "type":"REPORT_READY","title":"运营报表","content":"报表已生成",
                 "dedupeKey":"REPORT:9","occurredAt":"2026-10-16T10:00:00"}
                """.formatted(messageId);
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setMessageId(messageId)
                .setHeader("opsflow-tenant-id", 3L)
                .setHeader("x-first-death-queue", MessagingTopology.NOTIFICATION_QUEUE)
                .setHeader("x-first-death-exchange", MessagingTopology.EXCHANGE)
                .setHeader("x-first-death-reason", "rejected")
                .setHeader("x-death", List.of(Map.of("count", 2L,
                        "routing-keys", List.of(MessagingTopology.NOTIFICATION_ROUTING_KEY))))
                .build();
    }

    private DeadLetterMessage quarantinedMessage() {
        DeadLetterMessage message = new DeadLetterMessage();
        message.setId(31L);
        message.setTenantId(3L);
        message.setMessageId("f52a2690-58a7-4b94-a026-2f0dc871938d");
        message.setNotificationType("REPORT_READY");
        message.setTitle("运营报表");
        message.setRecipientId(7L);
        message.setDedupeKey("REPORT:9");
        message.setSourceQueue(MessagingTopology.NOTIFICATION_QUEUE);
        message.setSourceExchange(MessagingTopology.EXCHANGE);
        message.setOriginalRoutingKey(MessagingTopology.NOTIFICATION_ROUTING_KEY);
        message.setDeadLetterReason("rejected");
        message.setDeathCount(1L);
        message.setPayloadJson("{}");
        message.setHeadersJson("{}");
        message.setStatus(DeadLetterStatus.QUARANTINED);
        message.setRequeueCount(0);
        message.setQuarantinedAt(LocalDateTime.now().minusMinutes(5));
        message.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return message;
    }

    private void confirmPublish(boolean acknowledged, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(acknowledged, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(MessagingTopology.EXCHANGE), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
