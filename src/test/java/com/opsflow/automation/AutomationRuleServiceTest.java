package com.opsflow.automation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.auth.AuthService;
import com.opsflow.audit.AuditLogService;
import com.opsflow.notification.NotificationService;
import com.opsflow.sla.SlaPolicyService;
import com.opsflow.supportgroup.SupportGroup;
import com.opsflow.supportgroup.SupportGroupMapper;
import com.opsflow.supportgroup.SupportGroupMember;
import com.opsflow.supportgroup.SupportGroupMemberMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomationRuleServiceTest {
    @Mock
    private AutomationRuleMapper ruleMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private AuthService authService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private SlaPolicyService slaPolicyService;
    @Mock
    private SupportGroupMapper supportGroupMapper;
    @Mock
    private SupportGroupMemberMapper supportGroupMemberMapper;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AutomationRuleService service;

    @Test
    void appliesFirstMatchingRuleAndRecalculatesSla() {
        AutomationRule rule = new AutomationRule();
        rule.setId(11L);
        rule.setTenantId(3L);
        rule.setName("网络请求自动路由");
        rule.setEnabled(true);
        rule.setSortOrder(1);
        rule.setMatchCategory("网络");
        rule.setTargetAssigneeId(9L);
        rule.setTargetPriority(TicketPriority.HIGH);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(slaPolicyService.dueAt(eq(3L), eq(TicketPriority.HIGH), any()))
                .thenReturn(LocalDateTime.of(2026, 9, 21, 10, 0));
        when(ticketMapper.updateById(any(Ticket.class))).thenReturn(1);

        Ticket ticket = new Ticket();
        ticket.setId(20L);
        ticket.setTenantId(3L);
        ticket.setCategory("网络");
        ticket.setTitle("VPN 无法连接");
        ticket.setDescription("办公网络异常");
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setCreatedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
        ticket.setDueAt(LocalDateTime.of(2026, 9, 21, 10, 0));

        AutomationRuleService.AppliedRule applied = service.applyOnCreate(ticket, 7L);

        assertEquals(11L, applied.ruleId());
        assertEquals(9L, ticket.getAssigneeId());
        assertEquals(TicketPriority.HIGH, ticket.getPriority());
        verify(ticketMapper).updateById(ticket);
        verify(auditLogService).record(eq(3L), eq(7L), eq("TICKET"), eq(20L), eq("AUTOMATION_APPLIED"),
                any(), any(), eq("自动化规则「网络请求自动路由」已执行"));
    }

    @Test
    void rejectsRuleWithoutConditionsOrActions() {
        AutomationRuleService.RuleCommand command = new AutomationRuleService.RuleCommand(
                "无效规则", true, 1, null, null, null, null, null, null, null, null);

        assertThrows(RuntimeException.class, () -> service.create(command, 3L, 7L));
    }

    @Test
    void createsTenantScopedRuleAndPersistsActor() {
        when(authService.isStaffMember(9L, 3L)).thenReturn(true);
        when(ruleMapper.insert(any(AutomationRule.class))).thenAnswer(invocation -> {
            AutomationRule rule = invocation.getArgument(0, AutomationRule.class);
            rule.setId(12L);
            return 1;
        });
        AutomationRuleService.RuleCommand command = new AutomationRuleService.RuleCommand(
                "高优先级自动分派", true, 10, null, TicketPriority.URGENT, null,
                9L, null, null, null, null);

        AutomationRuleView view = service.create(command, 3L, 7L);

        assertEquals(3L, view.tenantId());
        assertEquals(7L, view.createdBy());
        assertEquals(TicketPriority.URGENT, view.matchPriority());
        verify(ruleMapper).insert(any(AutomationRule.class));
    }

    @Test
    void leastLoadedStrategyRoutesToGroupMemberWithLowestTenantWorkload() {
        AutomationRule rule = new AutomationRule();
        rule.setId(21L);
        rule.setTenantId(3L);
        rule.setName("应用故障团队分诊");
        rule.setEnabled(true);
        rule.setSortOrder(1);
        rule.setMatchCategory("应用故障");
        rule.setTargetGroupId(41L);
        rule.setAssignmentStrategy(AutomationAssignmentStrategy.LEAST_LOADED);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(supportGroupMapper.selectOne(any(Wrapper.class))).thenReturn(group(true));
        when(supportGroupMemberMapper.selectActiveForUpdate(41L, 3L))
                .thenReturn(List.of(member(9L, 1), member(10L, 2)));
        when(ticketMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                activeTicket(101L, 9L), activeTicket(102L, 10L), activeTicket(103L, 10L)));
        when(ticketMapper.updateById(any(Ticket.class))).thenReturn(1);

        Ticket ticket = newTicket("应用故障");
        AutomationRuleService.AppliedRule applied = service.applyOnCreate(ticket, 7L);

        assertEquals(21L, applied.ruleId());
        assertEquals(41L, ticket.getAssignmentGroupId());
        assertEquals(9L, ticket.getAssigneeId());
        verify(notificationService).notifyIfAbsent(eq(3L), eq(9L), eq("AUTOMATION_GROUP_ROUTED"),
                eq("新工单已自动分派"), any(), eq("AUTOMATION_GROUP_ROUTED:20:21"));
    }

    @Test
    void emptyGroupFallsBackToTeamQueueWithoutBlockingTicketCreation() {
        AutomationRule rule = new AutomationRule();
        rule.setId(22L);
        rule.setTenantId(3L);
        rule.setName("数据库请求进入队列");
        rule.setEnabled(true);
        rule.setMatchCategory("数据库");
        rule.setTargetGroupId(41L);
        rule.setAssignmentStrategy(AutomationAssignmentStrategy.LEAST_LOADED);
        when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));
        when(supportGroupMapper.selectOne(any(Wrapper.class))).thenReturn(group(true));
        when(supportGroupMemberMapper.selectActiveForUpdate(41L, 3L)).thenReturn(List.of());
        when(ticketMapper.updateById(any(Ticket.class))).thenReturn(1);

        Ticket ticket = newTicket("数据库");
        service.applyOnCreate(ticket, 7L);

        assertEquals(41L, ticket.getAssignmentGroupId());
        assertEquals(null, ticket.getAssigneeId());
    }

    @Test
    void rejectsAutomationTargetingDisabledGroup() {
        when(supportGroupMapper.selectOne(any(Wrapper.class))).thenReturn(group(false));
        AutomationRuleService.RuleCommand command = new AutomationRuleService.RuleCommand(
                "停用组规则", true, 10, "网络", null, null,
                null, 41L, AutomationAssignmentStrategy.QUEUE_ONLY, null, null);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.create(command, 3L, 7L));

        assertEquals("目标支持组已停用", exception.getMessage());
    }

    private SupportGroup group(boolean enabled) {
        SupportGroup group = new SupportGroup();
        group.setId(41L);
        group.setTenantId(3L);
        group.setName("应用支持组");
        group.setEnabled(enabled);
        return group;
    }

    private SupportGroupMember member(Long userId, int joinedOrder) {
        SupportGroupMember member = new SupportGroupMember();
        member.setId(100L + userId);
        member.setTenantId(3L);
        member.setGroupId(41L);
        member.setUserId(userId);
        member.setActive(true);
        member.setJoinedAt(LocalDateTime.of(2026, 9, joinedOrder, 9, 0));
        return member;
    }

    private Ticket newTicket(String category) {
        Ticket ticket = new Ticket();
        ticket.setId(20L);
        ticket.setTicketNo("OPF-AUTO20");
        ticket.setTenantId(3L);
        ticket.setRequesterId(7L);
        ticket.setCategory(category);
        ticket.setTitle("生产环境请求异常");
        ticket.setDescription("需要支持团队处理");
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setCreatedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
        ticket.setUpdatedAt(ticket.getCreatedAt());
        return ticket;
    }

    private Ticket activeTicket(Long id, Long assigneeId) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTenantId(3L);
        ticket.setAssigneeId(assigneeId);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        return ticket;
    }
}
