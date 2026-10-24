package com.opsflow.notification;

import com.opsflow.security.OutboundUrlPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeComNotificationChannelAdapterTest {
    @Test
    void serializesFrozenRenderedTemplateSnapshot() {
        WeComNotificationChannelAdapter adapter = new WeComNotificationChannelAdapter(
                new ObjectMapper(), mock(OutboundUrlPolicy.class), mock(HttpClient.class));
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setType("INCIDENT_DECLARED");
        notification.setTitle("原始标题");
        notification.setContent("原始正文");
        notification.setCreatedAt(LocalDateTime.of(2026, 10, 24, 14, 0));
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setRenderedTitle("模板标题");
        delivery.setRenderedContent("模板正文");

        String payload = adapter.payload(new NotificationChannelAdapter.DeliveryContext(delivery, notification));

        assertThat(payload).contains("模板标题", "模板正文", "INCIDENT_DECLARED");
        assertThat(payload).doesNotContain("原始标题", "原始正文");
    }
}
