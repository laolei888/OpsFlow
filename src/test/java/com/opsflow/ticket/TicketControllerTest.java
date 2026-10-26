package com.opsflow.ticket;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.supportgroup.SupportGroupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void createPreservesStructuredRequestContext() {
        TicketController.CreateTicketRequest request = new TicketController.CreateTicketRequest(
                "VPN 无法连接", "客户端持续连接超时", "研发团队无法远程办公",
                "恢复 VPN 访问", "重启客户端并切换网络", "网络", TicketPriority.HIGH);
        when(ticketService.create(any(Ticket.class), eq(7L), eq(3L), eq("create-001")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        controller.create(request, "create-001", requester());

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketService).create(captor.capture(), eq(7L), eq(3L), eq("create-001"));
        assertEquals("研发团队无法远程办公", captor.getValue().getImpactScope());
        assertEquals("恢复 VPN 访问", captor.getValue().getExpectedOutcome());
        assertEquals("重启客户端并切换网络", captor.getValue().getAttemptedActions());
    }

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
        when(ticketService.decideResolution(1L, TicketResolutionDecision.CONFIRM, null, 7L, 3L))
                .thenReturn(ticket);

        controller.transition(1L, TicketStatus.RESOLVED, requester());

        verify(ticketService).decideResolution(1L, TicketResolutionDecision.CONFIRM, null, 7L, 3L);
    }

    @Test
    void requesterCanReturnResolutionThroughDecisionEndpoint() {
        Ticket ticket = ticket(TicketStatus.WAITING_CONFIRMATION);
        when(ticketService.getVisible(1L, 3L, 7L)).thenReturn(ticket);
        when(ticketService.decideResolution(1L, TicketResolutionDecision.REOPEN,
                "问题仍然存在", 7L, 3L)).thenReturn(ticket);

        controller.decideResolution(1L, new TicketController.ResolutionDecisionRequest(
                TicketResolutionDecision.REOPEN, "问题仍然存在"), requester());

        verify(ticketService).decideResolution(1L, TicketResolutionDecision.REOPEN,
                "问题仍然存在", 7L, 3L);
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
