package com.opsflow.dashboard;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    @Mock
    private TicketMapper ticketMapper;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    void calculatesQueueHealthAndHonorsRequesterScope() {
        LocalDateTime now = LocalDateTime.now();
        Ticket ownOpen = ticket(1L, 7L, TicketStatus.OPEN, TicketPriority.URGENT, null, now.minusHours(1), now.plusHours(4));
        Ticket ownResolved = ticket(2L, 7L, TicketStatus.RESOLVED, TicketPriority.MEDIUM, 11L, now, now);
        Ticket otherTicket = ticket(3L, 9L, TicketStatus.IN_PROGRESS, TicketPriority.HIGH, 12L, now, now.plusHours(3));

        when(ticketMapper.selectList(any())).thenReturn(List.of(ownOpen, ownResolved));

        DashboardOverview overview = dashboardService.overview(1L, 7L);

        assertThat(overview.total()).isEqualTo(2);
        assertThat(overview.active()).isEqualTo(1);
        assertThat(overview.urgent()).isEqualTo(1);
        assertThat(overview.unassigned()).isEqualTo(1);
        assertThat(overview.assigned()).isEqualTo(1);
        assertThat(overview.resolved()).isEqualTo(1);
        assertThat(overview.statuses()).anyMatch(metric -> metric.key().equals("OPEN") && metric.count() == 1);
        assertThat(overview.priorities()).anyMatch(metric -> metric.key().equals("URGENT") && metric.count() == 1);
        assertThat(otherTicket.getRequesterId()).isEqualTo(9L);
    }

    @Test
    void returnsFixedLengthDailyTrendWithCreatedAndResolvedCounts() {
        LocalDateTime now = LocalDateTime.now();
        Ticket created = ticket(4L, 7L, TicketStatus.OPEN, TicketPriority.MEDIUM,
                null, now, now.plusHours(24));
        Ticket resolved = ticket(5L, 7L, TicketStatus.CLOSED, TicketPriority.LOW,
                11L, now.minusDays(1), now);
        resolved.setUpdatedAt(now);
        when(ticketMapper.selectList(any())).thenReturn(List.of(created, resolved));

        List<DashboardTrend> trends = dashboardService.trends(1L, 7L, 7);

        assertThat(trends).hasSize(7);
        assertThat(trends.get(6).created()).isEqualTo(1);
        assertThat(trends.get(6).resolved()).isEqualTo(1);
        assertThat(trends.get(5).created()).isEqualTo(1);
    }

    @Test
    void buildsDateRangeReportWithResolutionAndSlaMetrics() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Ticket resolvedOnTime = ticket(6L, 7L, TicketStatus.CLOSED, TicketPriority.HIGH,
                11L, now.minusDays(3), now.minusDays(1));
        resolvedOnTime.setDueAt(now.minusHours(12));
        resolvedOnTime.setUpdatedAt(now.minusDays(1));

        Ticket resolvedLate = ticket(7L, 7L, TicketStatus.RESOLVED, TicketPriority.MEDIUM,
                12L, now.minusDays(4), now.minusDays(1));
        resolvedLate.setDueAt(now.minusDays(3));
        resolvedLate.setUpdatedAt(now.minusDays(1));

        Ticket activeOverdue = ticket(8L, 9L, TicketStatus.IN_PROGRESS, TicketPriority.URGENT,
                null, now.minusDays(20), now.minusHours(2));
        when(ticketMapper.selectList(any())).thenReturn(List.of(resolvedOnTime, resolvedLate, activeOverdue));

        DashboardReport report = dashboardService.report(1L, now.toLocalDate().minusDays(6), now.toLocalDate());

        assertThat(report.createdTickets()).isEqualTo(2);
        assertThat(report.resolvedTickets()).isEqualTo(2);
        assertThat(report.activeTickets()).isEqualTo(1);
        assertThat(report.overdueTickets()).isEqualTo(1);
        assertThat(report.averageResolutionHours()).isEqualTo(60.0);
        assertThat(report.slaComplianceRate()).isEqualTo(50);
        assertThat(report.slaResolvedSample()).isEqualTo(2);
        assertThat(report.assignees()).hasSize(3);
        assertThat(report.categories()).extracting("category").contains("未分类");
    }
    private Ticket ticket(Long id, Long requesterId, TicketStatus status, TicketPriority priority,
                          Long assigneeId, LocalDateTime createdAt, LocalDateTime dueAt) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTenantId(1L);
        ticket.setRequesterId(requesterId);
        ticket.setStatus(status);
        ticket.setPriority(priority);
        ticket.setAssigneeId(assigneeId);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        ticket.setDueAt(dueAt);
        return ticket;
    }
}
