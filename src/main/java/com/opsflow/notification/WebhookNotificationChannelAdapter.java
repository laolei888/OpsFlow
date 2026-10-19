package com.opsflow.notification;

import com.opsflow.security.OutboundUrlPolicy;
import com.opsflow.webhook.WebhookEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebhookNotificationChannelAdapter implements NotificationChannelAdapter {
    private final ObjectMapper objectMapper;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final HttpClient httpClient;

    public WebhookNotificationChannelAdapter(ObjectMapper objectMapper, OutboundUrlPolicy outboundUrlPolicy) {
        this.objectMapper = objectMapper;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WEBHOOK;
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context) throws Exception {
        NotificationDelivery delivery = context.delivery();
        Notification notification = context.notification();
        String payload = payload(delivery, notification);
        String signature = WebhookEventPublisher.sign(delivery.getDeliverySecret(), payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(outboundUrlPolicy.validate(delivery.getDestination()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("User-Agent", "OpsFlow-Notification/1.0")
                .header("X-OpsFlow-Notification", String.valueOf(notification.getId()))
                .header("X-OpsFlow-Event", notification.getType())
                .header("X-OpsFlow-Signature", "sha256=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            boolean retryable = status >= 500 || status == 408 || status == 429;
            throw new NotificationDeliveryException("目标服务返回 HTTP " + status, status, retryable);
        }
        return new DeliveryResult(status);
    }

    private String payload(NotificationDelivery delivery, Notification notification) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("deliveryId", delivery.getId());
        value.put("notificationId", notification.getId());
        value.put("eventType", notification.getType());
        value.put("tenantId", notification.getTenantId());
        value.put("recipientId", notification.getRecipientId());
        value.put("ticketId", notification.getTicketId());
        value.put("title", notification.getTitle());
        value.put("content", notification.getContent());
        value.put("occurredAt", notification.getCreatedAt());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new NotificationDeliveryException("Webhook 负载序列化失败", null, false);
        }
    }
}
