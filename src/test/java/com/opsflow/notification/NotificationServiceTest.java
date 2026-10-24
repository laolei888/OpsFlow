package com.opsflow.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationDeliveryService deliveryService;
    @InjectMocks private NotificationService notificationService;

    @Test
    void createsIdempotentNotificationForConsumer() {
        when(notificationMapper.selectCount(any())).thenReturn(0L);
        NotificationPreferenceService.ResolvedPreference preference = new NotificationPreferenceService.ResolvedPreference(
                true, false, false, false, null, null, null, null);
        when(deliveryService.resolve(1L, 8L, "SLA_OVERDUE")).thenReturn(preference);

        boolean created = notificationService.notifyIfAbsent(1L, 8L, 21L,
                "SLA_OVERDUE", "SLA 已逾期", "请立即处理", "SLA_OVERDUE:21");

        assertThat(created).isTrue();
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo(8L);
        assertThat(captor.getValue().getTicketId()).isEqualTo(21L);
        assertThat(captor.getValue().getDedupeKey()).isEqualTo("SLA_OVERDUE:21");
        assertThat(captor.getValue().getInAppEnabled()).isTrue();
        verify(deliveryService).plan(captor.getValue(), preference);
    }

    @Test
    void ignoresNotificationAlreadyConsumed() {
        when(notificationMapper.selectCount(any())).thenReturn(1L);

        boolean created = notificationService.notifyIfAbsent(1L, 8L,
                "TICKET_CREATED", "工单已创建", "内容", "TICKET_CREATED:21");

        assertThat(created).isFalse();
        verify(notificationMapper, never()).insert(any(Notification.class));
    }
}
