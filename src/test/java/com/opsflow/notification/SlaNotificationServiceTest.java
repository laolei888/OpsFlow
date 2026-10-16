package com.opsflow.notification;

import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.messaging.AsyncNotificationPublisher;
import com.opsflow.oncall.OnCallRecipientResolver;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaNotificationServiceTest {
    @Mock private TicketMapper ticketMapper;
    @Mock private AuthService authService;
    @Mock private OnCallRecipientResolver onCallRecipientResolver;
    @Mock private AsyncNotificationPublisher notificationPublisher;
    @InjectMocks private SlaNotificationService service;

    @Test
    void scanQueuesOverdueNotificationForUnassignedTicket() {
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
        when(onCallRecipientResolver.activePrimaryRecipients(any(), any())).thenReturn(Set.of());
        when(notificationPublisher.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        int queued = service.scanTenant(1L);

        assertThat(queued).isEqualTo(1);
        verify(notificationPublisher).enqueue(eq(1L), eq(8L), eq(21L), eq("SLA_OVERDUE"),
                eq("SLA 已逾期"), contains("OPF-00000021"), contains("SLA_OVERDUE:21"),
                eq("TICKET"), eq(21L));
    }
}
