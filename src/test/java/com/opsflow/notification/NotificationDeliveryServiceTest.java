package com.opsflow.notification;

import com.opsflow.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {
    @Mock private NotificationDeliveryMapper deliveryMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationPreferenceService preferenceService;
    @Mock private NotificationTemplateService templateService;
    @Mock private AuditLogService auditLogService;
    @Mock private NotificationDeliveryMetrics metrics;
    @Mock private PlatformTransactionManager transactionManager;

    @Test
    void plansIndependentDeliveryForEveryEnabledChannel() {
        NotificationDeliveryService service = new NotificationDeliveryService(deliveryMapper, notificationMapper,
                preferenceService, templateService, auditLogService, metrics, transactionManager, List.of());
        Notification notification = notification();
        NotificationPreferenceService.ResolvedPreference preference =
                new NotificationPreferenceService.ResolvedPreference(true, true, true, true,
                        "agent@example.com", "https://notify.example.com/opsflow", "secret",
                        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test");
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(2);
        when(preferenceService.deliveryWindow(org.mockito.ArgumentMatchers.eq(preference),
                org.mockito.ArgumentMatchers.eq("INCIDENT_DECLARED"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NotificationPreferenceService.DeliveryWindowDecision(
                        scheduledAt, true, "发送窗口将在两小时后开启"));
        when(templateService.render(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new NotificationTemplateService.RenderedTemplate(null,
                        notification.getTitle(), notification.getContent()));

        service.plan(notification, preference);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper, times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDelivery::getChannel)
                .containsExactly(NotificationChannel.IN_APP, NotificationChannel.EMAIL,
                        NotificationChannel.WEBHOOK, NotificationChannel.WECOM);
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo(NotificationDeliveryStatus.DELIVERED);
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(captor.getAllValues().get(1).getNextAttemptAt()).isEqualTo(scheduledAt);
        assertThat(captor.getAllValues().get(1).getScheduledReason()).contains("两小时后");
        assertThat(captor.getAllValues().get(2).getDeliverySecret()).isEqualTo("secret");
    }

    @Test
    void recordsSkippedInAppDeliveryWhenUserDisablesIt() {
        NotificationDeliveryService service = new NotificationDeliveryService(deliveryMapper, notificationMapper,
                preferenceService, templateService, auditLogService, metrics, transactionManager, List.of());

        when(templateService.render(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NotificationTemplateService.RenderedTemplate(null, "重大事件", "请立即响应"));

        NotificationPreferenceService.ResolvedPreference preference = new NotificationPreferenceService.ResolvedPreference(
                false, false, false, false, null, null, null, null);
        when(preferenceService.deliveryWindow(org.mockito.ArgumentMatchers.eq(preference),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NotificationPreferenceService.DeliveryWindowDecision(
                        LocalDateTime.now(), false, null));

        service.plan(notification(), preference);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationDeliveryStatus.SKIPPED);
        assertThat(captor.getValue().getLastError()).contains("关闭");
    }

    @Test
    void manualRetryRefreshesWebhookDestinationAndSecretFromCurrentPreference() {
        NotificationDeliveryService service = new NotificationDeliveryService(deliveryMapper, notificationMapper,
                preferenceService, templateService, auditLogService, metrics, transactionManager, List.of());
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setId(91L);
        delivery.setTenantId(1L);
        delivery.setRecipientId(7L);
        delivery.setNotificationId(22L);
        delivery.setNotificationType("INCIDENT_DECLARED");
        delivery.setChannel(NotificationChannel.WEBHOOK);
        delivery.setDestination("https://old.example.com/hook");
        delivery.setDeliverySecret("old-secret");
        delivery.setStatus(NotificationDeliveryStatus.DEAD);
        delivery.setAttemptCount(5);
        delivery.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        when(deliveryMapper.selectForUpdate(91L, 1L)).thenReturn(delivery);
        when(preferenceService.resolve(1L, 7L, "INCIDENT_DECLARED"))
                .thenReturn(new NotificationPreferenceService.ResolvedPreference(true, false, true, false,
                        null, "https://new.example.com/hook", "new-secret", null));
        when(preferenceService.deliveryWindow(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("INCIDENT_DECLARED"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NotificationPreferenceService.DeliveryWindowDecision(
                        LocalDateTime.now(), false, null));
        when(notificationMapper.selectById(22L)).thenReturn(notification());

        NotificationDeliveryService.DeliveryView retried = service.retry(
                91L, 1L, 3L, "目标地址和密钥已经修复");

        assertThat(delivery.getDestination()).isEqualTo("https://new.example.com/hook");
        assertThat(delivery.getDeliverySecret()).isEqualTo("new-secret");
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(retried.destinationPreview()).isEqualTo("https://new.example.com/hook");
        verify(deliveryMapper).updateById(delivery);
    }

    private Notification notification() {
        Notification notification = new Notification();
        notification.setId(22L);
        notification.setTenantId(1L);
        notification.setRecipientId(7L);
        notification.setType("INCIDENT_DECLARED");
        notification.setTitle("重大事件");
        notification.setContent("请立即响应");
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }
}
