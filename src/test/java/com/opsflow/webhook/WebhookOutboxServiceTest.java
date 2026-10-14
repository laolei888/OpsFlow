package com.opsflow.webhook;

import com.opsflow.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookOutboxServiceTest {
    @Mock private WebhookOutboxMapper outboxMapper;
    @Mock private WebhookEndpointMapper endpointMapper;
    @Mock private WebhookDeliveryMapper deliveryMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private PlatformTransactionManager transactionManager;

    private WebhookOutboxService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new WebhookOutboxService(outboxMapper, endpointMapper, deliveryMapper,
                auditLogService, transactionManager);
    }

    @Test
    void materializesOnlyMatchingEndpointsAndPublishesOutboxEvent() {
        WebhookOutboxEvent event = event(WebhookOutboxStatus.PENDING);
        when(outboxMapper.selectList(any())).thenReturn(List.of(event));
        when(outboxMapper.selectForUpdate(31L)).thenReturn(event);
        when(endpointMapper.selectList(any())).thenReturn(List.of(
                endpoint(10L, "TICKET_CREATED,PROBLEM_CREATED"),
                endpoint(11L, "COMMENT_ADDED")));

        int published = service.dispatchPending(20);

        assertEquals(1, published);
        assertEquals(WebhookOutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        WebhookDelivery delivery = captor.getValue();
        assertEquals(10L, delivery.getEndpointId());
        assertEquals("event-0001", delivery.getEventId());
        assertEquals(WebhookDeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(WebhookEventPublisher.sign("unit-secret", event.getPayloadJson()), delivery.getSignature());
    }

    @Test
    void eventWithoutSubscribersIsStillMarkedPublished() {
        WebhookOutboxEvent event = event(WebhookOutboxStatus.PENDING);
        when(outboxMapper.selectList(any())).thenReturn(List.of(event));
        when(outboxMapper.selectForUpdate(31L)).thenReturn(event);
        when(endpointMapper.selectList(any())).thenReturn(List.of());

        assertEquals(1, service.dispatchPending(20));

        assertEquals(WebhookOutboxStatus.PUBLISHED, event.getStatus());
        verify(deliveryMapper, never()).insert(any(WebhookDelivery.class));
    }

    @Test
    void materializationFailureMovesEventToRetryableFailedState() {
        WebhookOutboxEvent candidate = event(WebhookOutboxStatus.PENDING);
        WebhookOutboxEvent retryState = event(WebhookOutboxStatus.PENDING);
        when(outboxMapper.selectList(any())).thenReturn(List.of(candidate));
        when(outboxMapper.selectForUpdate(31L)).thenReturn(candidate, retryState);
        when(endpointMapper.selectList(any())).thenReturn(List.of(endpoint(10L, "*")));
        doThrow(new IllegalStateException("database unavailable"))
                .when(deliveryMapper).insert(any(WebhookDelivery.class));

        assertEquals(0, service.dispatchPending(20));

        assertEquals(WebhookOutboxStatus.FAILED, retryState.getStatus());
        assertEquals(1, retryState.getAttemptCount());
        assertTrue(retryState.getLastError().contains("database unavailable"));
        assertTrue(retryState.getNextAttemptAt().isAfter(LocalDateTime.now().minusSeconds(1)));
    }

    @Test
    void administratorCanManuallyRequeueDeadEvent() {
        WebhookOutboxEvent event = event(WebhookOutboxStatus.DEAD);
        event.setAttemptCount(8);
        when(outboxMapper.selectForUpdateInTenant(31L, 3L)).thenReturn(event);

        WebhookOutboxEvent result = service.retry(31L, 3L, 7L);

        assertEquals(WebhookOutboxStatus.PENDING, result.getStatus());
        assertEquals(0, result.getAttemptCount());
        verify(auditLogService).record(eq(3L), eq(7L), eq("WEBHOOK_OUTBOX"), eq(31L),
                eq("WEBHOOK_OUTBOX_RETRY"), eq(null), eq(result), any());
    }

    private WebhookOutboxEvent event(WebhookOutboxStatus status) {
        WebhookOutboxEvent event = new WebhookOutboxEvent();
        event.setId(31L);
        event.setTenantId(3L);
        event.setAuditLogId(41L);
        event.setEventId("event-0001");
        event.setEventType("TICKET_CREATED");
        event.setResourceType("TICKET");
        event.setResourceId(99L);
        event.setPayloadJson("{\"eventType\":\"TICKET_CREATED\"}");
        event.setStatus(status);
        event.setAttemptCount(0);
        event.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        event.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        event.setUpdatedAt(LocalDateTime.now().minusMinutes(2));
        return event;
    }

    private WebhookEndpoint endpoint(Long id, String eventTypes) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(id);
        endpoint.setTenantId(3L);
        endpoint.setUrl("https://example.com/hooks");
        endpoint.setSecret("unit-secret");
        endpoint.setEventTypes(eventTypes);
        endpoint.setEnabled(true);
        return endpoint;
    }
}
