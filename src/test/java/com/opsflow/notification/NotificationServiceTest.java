package com.opsflow.notification;

import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import com.opsflow.oncall.OnCallRecipientResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private AuthService authService;
    @Mock
    private OnCallRecipientResolver onCallRecipientResolver;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void scanCreatesOneOverdueNotificationForUnassignedTicket() {
        Ticket ticket = new Ticket();
        ticket.setId(21L);
        ticket.setTenantId(1L);
        ticket.setTicketNo("OPF-00000021");
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setDueAt(LocalDateTime.now().minusHours(1));

        when(authService.staffMembers(1L))
                .thenReturn(List.of(new StaffSummary(8L, "agent", "客服一号", List.of("AGENT"))));
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket));
        when(notificationMapper.selectCount(any())).thenReturn(0L);
        when(onCallRecipientResolver.activePrimaryRecipients(any(), any())).thenReturn(Set.of());

        int created = notificationService.scanTenant(1L);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo(8L);
        assertThat(captor.getValue().getType()).isEqualTo("SLA_OVERDUE");
        assertThat(captor.getValue().getTicketId()).isEqualTo(21L);
    }
}