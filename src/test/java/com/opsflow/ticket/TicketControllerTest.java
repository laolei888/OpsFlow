package com.opsflow.ticket;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.supportgroup.SupportGroupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketControllerTest {
    @Mock
    private TicketService ticketService;
    @Mock
    private AuthService authService;
    @Mock
    private SupportGroupService supportGroupService;

    @InjectMocks
    private TicketController controller;

    @Test
    void requesterCannotMoveOpenTicketIntoAgentWorkflow() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        when(ticketService.getVisible(1L, 3L, 7L)).thenReturn(ticket);

        assertThrows(BusinessException.class,
                () -> controller.transition(1L, TicketStatus.IN_PROGRESS, requester()));

        verify(ticketService, never()).transition(1L, TicketStatus.IN_PROGRESS, 7L, 3L);
    }

    @Test
    void requesterCanConfirmResolvedTicket() {
        Ticket ticket = ticket(TicketStatus.WAITING_CONFIRMATION);
        when(ticketService.getVisible(1L, 3L, 7L)).thenReturn(ticket);
        when(ticketService.transition(1L, TicketStatus.RESOLVED, 7L, 3L)).thenReturn(ticket);

        controller.transition(1L, TicketStatus.RESOLVED, requester());

        verify(ticketService).transition(1L, TicketStatus.RESOLVED, 7L, 3L);
    }

    @Test
    void agentCanAdvanceNormalWorkflow() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        AuthPrincipal agent = new AuthPrincipal(9L, 3L, "agent",
                List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        when(ticketService.getVisible(1L, 3L, null)).thenReturn(ticket);
        when(ticketService.transition(1L, TicketStatus.IN_PROGRESS, 9L, 3L)).thenReturn(ticket);

        controller.transition(1L, TicketStatus.IN_PROGRESS, agent);

        verify(ticketService).transition(1L, TicketStatus.IN_PROGRESS, 9L, 3L);
    }

    private AuthPrincipal requester() {
        return new AuthPrincipal(7L, 3L, "requester",
                List.of(new SimpleGrantedAuthority("ROLE_REQUESTER")));
    }

    private Ticket ticket(TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setTenantId(3L);
        ticket.setRequesterId(7L);
        ticket.setStatus(status);
        return ticket;
    }
}
