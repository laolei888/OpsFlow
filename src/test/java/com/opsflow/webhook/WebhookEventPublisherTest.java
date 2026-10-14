package com.opsflow.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebhookEventPublisherTest {
    @Mock private WebhookOutboxMapper outboxMapper;

    @Test
    void persistsAuditEventAsTransactionalOutboxRecord() {
        WebhookEventPublisher publisher = new WebhookEventPublisher(outboxMapper, new ObjectMapper());
        AuditLog audit = audit(41L, "TICKET_CREATED");
        audit.setBeforeValue("{\"status\":\"OPEN\"}");
        audit.setAfterValue("{\"status\":\"IN_PROGRESS\"}");

        publisher.publish(audit);

        ArgumentCaptor<WebhookOutboxEvent> captor = ArgumentCaptor.forClass(WebhookOutboxEvent.class);
        verify(outboxMapper).insert(captor.capture());
        WebhookOutboxEvent event = captor.getValue();
        assertEquals(41L, event.getAuditLogId());
        assertEquals(3L, event.getTenantId());
        assertEquals(WebhookOutboxStatus.PENDING, event.getStatus());
        assertEquals(64, event.getEventId().length());
        assertTrue(event.getPayloadJson().contains("TICKET_CREATED"));
        assertTrue(event.getPayloadJson().contains("IN_PROGRESS"));
        assertNotNull(event.getNextAttemptAt());
    }

    @Test
    void duplicateAuditEventIsIdempotentlyIgnored() {
        WebhookEventPublisher publisher = new WebhookEventPublisher(outboxMapper, new ObjectMapper());
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(outboxMapper).insert(any(WebhookOutboxEvent.class));

        publisher.publish(audit(41L, "PROBLEM_CREATED"));

        verify(outboxMapper).insert(any(WebhookOutboxEvent.class));
    }

    @Test
    void hmacSignatureIsDeterministic() {
        String first = WebhookEventPublisher.sign("secret", "payload");
        String second = WebhookEventPublisher.sign("secret", "payload");

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotNull(first);
    }

    private AuditLog audit(Long id, String action) {
        AuditLog audit = new AuditLog();
        audit.setId(id);
        audit.setTenantId(3L);
        audit.setActorId(7L);
        audit.setResourceType("TICKET");
        audit.setResourceId(99L);
        audit.setAction(action);
        audit.setDetail("unit test");
        audit.setCreatedAt(LocalDateTime.of(2026, 10, 9, 10, 0));
        return audit;
    }
}
