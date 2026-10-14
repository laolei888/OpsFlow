package com.opsflow.webhook;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.security.OutboundUrlPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebhookEndpointServiceTest {
    @Mock
    private WebhookEndpointMapper endpointMapper;
    @Mock
    private WebhookDeliveryMapper deliveryMapper;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private OutboundUrlPolicy outboundUrlPolicy;

    @Test
    void createsEndpointWithGeneratedSecretAndMaskedView() {
        WebhookEndpointService service = new WebhookEndpointService(endpointMapper, deliveryMapper, auditLogService,
                outboundUrlPolicy);
        doAnswer(invocation -> {
            WebhookEndpoint endpoint = invocation.getArgument(0);
            endpoint.setId(12L);
            return 1;
        }).when(endpointMapper).insert(any(WebhookEndpoint.class));

        WebhookEndpointService.WebhookEndpointView view = service.create(
                new WebhookEndpointService.WebhookCommand("告警同步", "https://example.com/hooks", "", "ticket_created, problem_created"),
                3L, 7L);

        assertEquals(12L, view.id());
        assertEquals("TICKET_CREATED,PROBLEM_CREATED", view.eventTypes());
        assertTrue(view.secretPreview().startsWith("••••"));
        assertTrue(!view.secretPreview().contains("https"));
        verify(auditLogService).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsNonHttpWebhookUrl() {
        WebhookEndpointService service = new WebhookEndpointService(endpointMapper, deliveryMapper, auditLogService,
                outboundUrlPolicy);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.create(
                new WebhookEndpointService.WebhookCommand("坏地址", "file:///tmp/hook", "secret", "*"),
                3L, 7L));

        assertEquals("Webhook URL 必须是带域名的 HTTP 或 HTTPS 地址", exception.getMessage());
    }

    @Test
    void rejectsMalformedEventType() {
        WebhookEndpointService service = new WebhookEndpointService(endpointMapper, deliveryMapper, auditLogService,
                outboundUrlPolicy);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.create(
                new WebhookEndpointService.WebhookCommand("格式检查", "https://example.com/hooks", "secret", "TICKET CREATED"),
                3L, 7L));

        assertTrue(exception.getMessage().contains("事件类型格式无效"));
    }
}
