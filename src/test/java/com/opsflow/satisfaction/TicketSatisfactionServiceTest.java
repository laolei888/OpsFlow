package com.opsflow.satisfaction;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.common.BusinessException;
import com.opsflow.supportgroup.SupportGroup;
import com.opsflow.supportgroup.SupportGroupMapper;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketSatisfactionServiceTest {
    @Mock
    private TicketSatisfactionResponseMapper responseMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private SupportGroupMapper supportGroupMapper;
    @Mock
    private AuthService authService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TicketSatisfactionService satisfactionService;

    @Test
    void submitsResponseWithAssignmentSnapshotsAndNormalizedTags() {
        Ticket ticket = reviewableTicket();
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(responseMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(responseMapper.insert(any(TicketSatisfactionResponse.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TicketSatisfactionResponse.class).setId(31L);
            return 1;
        });

        TicketSatisfactionService.SurveyItem item = satisfactionService.submit(
                11L, 5, List.of("professional", "FAST_RESPONSE", "professional"),
                " 处理很快，谢谢 ", 7L, 3L);

        ArgumentCaptor<TicketSatisfactionResponse> captor = ArgumentCaptor.forClass(TicketSatisfactionResponse.class);
        verify(responseMapper).insert(captor.capture());
        TicketSatisfactionResponse saved = captor.getValue();
        assertEquals(9L, saved.getAssigneeId());
        assertEquals(5L, saved.getAssignmentGroupId());
        assertEquals("PROFESSIONAL,FAST_RESPONSE", saved.getTags());
        assertEquals("处理很快，谢谢", saved.getComment());
        assertEquals(5, item.rating());
        assertFalse(item.eligible());
        verify(auditLogService).record(eq(3L), eq(7L), eq("TICKET"), eq(11L),
                eq("SATISFACTION_SUBMITTED"), eq(null), any(), eq("申请人提交服务满意度评价"));
    }

    @Test
    void rejectsResponseFromSomeoneOtherThanRequester() {
        Ticket ticket = reviewableTicket();
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(BusinessException.class,
                () -> satisfactionService.submit(11L, 5, List.of(), null, 8L, 3L));

        verify(responseMapper, never()).insert(any(TicketSatisfactionResponse.class));
    }

    @Test
    void rejectsResponseBeforeTicketIsResolved() {
        Ticket ticket = reviewableTicket();
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(BusinessException.class,
                () -> satisfactionService.submit(11L, 4, List.of(), null, 7L, 3L));

        verify(responseMapper, never()).insert(any(TicketSatisfactionResponse.class));
    }

    @Test
    void listsPendingAndCompletedSurveysForCurrentRequester() {
        Ticket first = reviewableTicket();
        Ticket second = reviewableTicket();
        second.setId(12L);
        second.setTicketNo("OPF-SAT-002");
        when(ticketMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        TicketSatisfactionResponse response = response(12L, 4, 9L, 5L);
        when(responseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(response));

        List<TicketSatisfactionService.SurveyItem> result = satisfactionService.mySurveys(7L, 3L, 100);

        assertEquals(2, result.size());
        assertEquals(true, result.get(0).eligible());
        assertEquals(4, result.get(1).rating());
    }

    @Test
    void buildsTenantQualityAnalyticsByGroupAndAssignee() {
        TicketSatisfactionResponse five = response(11L, 5, 9L, 5L);
        TicketSatisfactionResponse four = response(12L, 4, 9L, 5L);
        TicketSatisfactionResponse one = response(13L, 1, 10L, 6L);
        SatisfactionAggregateRow aggregate = new SatisfactionAggregateRow();
        aggregate.setTotalResponses(3L);
        aggregate.setAverageRating(new BigDecimal("3.33"));
        aggregate.setPositiveCount(2L);
        aggregate.setLowRatingCount(1L);
        when(responseMapper.summarize(eq(3L), any(LocalDateTime.class))).thenReturn(aggregate);

        SatisfactionDistributionRow scoreFive = new SatisfactionDistributionRow();
        scoreFive.setScore(5);
        scoreFive.setResponses(1L);
        SatisfactionDistributionRow scoreFour = new SatisfactionDistributionRow();
        scoreFour.setScore(4);
        scoreFour.setResponses(1L);
        SatisfactionDistributionRow scoreOne = new SatisfactionDistributionRow();
        scoreOne.setScore(1);
        scoreOne.setResponses(1L);
        when(responseMapper.distribution(eq(3L), any(LocalDateTime.class)))
                .thenReturn(List.of(scoreFive, scoreFour, scoreOne));

        SatisfactionDimensionRow serviceDeskMetric = metric(5L, 2L, "4.50", 2L);
        SatisfactionDimensionRow networkMetric = metric(6L, 1L, "1.00", 0L);
        when(responseMapper.groupMetrics(eq(3L), any(LocalDateTime.class)))
                .thenReturn(List.of(serviceDeskMetric, networkMetric));
        SatisfactionDimensionRow agentOneMetric = metric(9L, 2L, "4.50", 2L);
        SatisfactionDimensionRow agentTwoMetric = metric(10L, 1L, "1.00", 0L);
        when(responseMapper.assigneeMetrics(eq(3L), any(LocalDateTime.class)))
                .thenReturn(List.of(agentOneMetric, agentTwoMetric));
        when(responseMapper.recent(eq(3L), any(LocalDateTime.class), eq(30)))
                .thenReturn(List.of(five, four, one));

        SupportGroup serviceDesk = new SupportGroup();
        serviceDesk.setId(5L);
        serviceDesk.setName("服务台");
        SupportGroup network = new SupportGroup();
        network.setId(6L);
        network.setName("网络组");
        when(supportGroupMapper.selectList(any(Wrapper.class))).thenReturn(List.of(serviceDesk, network));
        when(authService.staffMembers(3L)).thenReturn(List.of(
                new StaffSummary(9L, "agent.one", "客服一", List.of("AGENT")),
                new StaffSummary(10L, "agent.two", "客服二", List.of("AGENT"))));
        when(ticketMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                ticket(11L, "OPF-011"), ticket(12L, "OPF-012"), ticket(13L, "OPF-013")));

        TicketSatisfactionService.AnalyticsSummary analytics = satisfactionService.analytics(3L, 30, 30);

        assertEquals(3, analytics.totalResponses());
        assertEquals(3.33, analytics.averageRating());
        assertEquals(66.7, analytics.positiveRate());
        assertEquals(1, analytics.lowRatingCount());
        assertEquals(2, analytics.distribution().get(5) + analytics.distribution().get(4));
        assertEquals("服务台", analytics.groups().get(0).name());
        assertEquals(4.5, analytics.groups().get(0).averageRating());
        assertEquals("客服一", analytics.assignees().get(0).name());
        assertEquals(3, analytics.recent().size());
    }

    private Ticket reviewableTicket() {
        Ticket ticket = ticket(11L, "OPF-SAT-001");
        ticket.setTenantId(3L);
        ticket.setRequesterId(7L);
        ticket.setAssigneeId(9L);
        ticket.setAssignmentGroupId(5L);
        ticket.setStatus(TicketStatus.RESOLVED);
        return ticket;
    }

    private Ticket ticket(Long id, String no) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTenantId(3L);
        ticket.setTicketNo(no);
        ticket.setTitle("测试工单 " + no);
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticket;
    }

    private TicketSatisfactionResponse response(Long ticketId, int rating, Long assigneeId, Long groupId) {
        TicketSatisfactionResponse response = new TicketSatisfactionResponse();
        response.setId(ticketId + 100);
        response.setTenantId(3L);
        response.setTicketId(ticketId);
        response.setRequesterId(7L);
        response.setAssigneeId(assigneeId);
        response.setAssignmentGroupId(groupId);
        response.setRating(rating);
        response.setTags("PROFESSIONAL");
        response.setComment("评价 " + rating);
        response.setSubmittedAt(LocalDateTime.now().minusMinutes(ticketId));
        return response;
    }

    private SatisfactionDimensionRow metric(Long id, long responses, String average, long positive) {
        SatisfactionDimensionRow row = new SatisfactionDimensionRow();
        row.setDimensionId(id);
        row.setResponses(responses);
        row.setAverageRating(new BigDecimal(average));
        row.setPositiveCount(positive);
        return row;
    }
}
