package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.automation.AutomationRuleService;
import com.opsflow.messaging.AsyncNotificationPublisher;
import com.opsflow.sla.SlaPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {
    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private SlaPolicyService slaPolicyService;

    @Mock
    private AutomationRuleService automationRuleService;

    @Mock
    private TicketIdempotencyKeyMapper idempotencyKeyMapper;

    @Mock
    private AsyncNotificationPublisher notificationPublisher;

    @InjectMocks
    private TicketService ticketService;

    @Test
    void createsTicketWithTenantAndRequester() {
        Ticket ticket = new Ticket();
        ticket.setTitle("VPN 无法连接");
        ticket.setDescription("无法访问公司 VPN");
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Ticket.class).setId(1L);
            return 1;
        });
        when(slaPolicyService.dueAt(eq(3L), eq(TicketPriority.MEDIUM), any()))
                .thenAnswer(invocation -> invocation.getArgument(2, java.time.LocalDateTime.class).plusHours(24));

        Ticket created = ticketService.create(ticket, 7L, 3L);

        assertEquals(3L, created.getTenantId());
        assertEquals(7L, created.getRequesterId());
        assertEquals(TicketStatus.OPEN, created.getStatus());
        assertEquals(TicketPriority.MEDIUM, created.getPriority());
        assertNotNull(created.getDueAt());
        verify(ticketMapper).insert(ticket);
    }

    @Test

    void updatesEditableFieldsAndRecordsAudit() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        ticket.setTitle("旧标题");
        ticket.setDescription("旧描述");
        ticket.setImpactScope("单个用户");
        ticket.setExpectedOutcome("恢复连接");
        ticket.setAttemptedActions("重新登录");
        ticket.setCategory("网络");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketMapper.updateById(ticket)).thenReturn(1);

        Ticket updated = ticketService.updateDetails(
                1L, " 新标题 ", " 新描述 ", " 全体研发人员 ", " 恢复远程访问 ",
                " 重启客户端 ", "账号", TicketPriority.URGENT, 9L, 3L);

        assertEquals("新标题", updated.getTitle());
        assertEquals("新描述", updated.getDescription());
        assertEquals("全体研发人员", updated.getImpactScope());
        assertEquals("恢复远程访问", updated.getExpectedOutcome());
        assertEquals("重启客户端", updated.getAttemptedActions());
        assertEquals("账号", updated.getCategory());
        assertEquals(TicketPriority.URGENT, updated.getPriority());
        verify(ticketMapper).updateById(ticket);
        verify(auditLogService).record(eq(3L), eq(9L), eq("TICKET"), eq(1L),
                eq("TICKET_UPDATED"), any(), any(), eq("编辑工单信息"));
    }

    @Test
    void skipsNoopTicketUpdateWithoutAudit() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        ticket.setTitle("当前标题");
        ticket.setDescription("当前描述");
        ticket.setImpactScope("单个用户");
        ticket.setExpectedOutcome("恢复访问");
        ticket.setAttemptedActions("清理缓存");
        ticket.setCategory("网络");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        Ticket unchanged = ticketService.updateDetails(
                1L, "当前标题", "当前描述", "单个用户", "恢复访问", "清理缓存",
                "网络", TicketPriority.MEDIUM, 9L, 3L);

        assertEquals(ticket, unchanged);
        verify(ticketMapper, never()).updateById(any(Ticket.class));
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }
    @Test
    void reusesExistingTicketForRepeatedIdempotencyKey() {
        TicketIdempotencyKey persistedKey = new TicketIdempotencyKey();
        when(idempotencyKeyMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation ->
                persistedKey.getTicketId() == null ? null : persistedKey);
        when(idempotencyKeyMapper.insert(any(TicketIdempotencyKey.class))).thenAnswer(invocation -> {
            TicketIdempotencyKey key = invocation.getArgument(0, TicketIdempotencyKey.class);
            persistedKey.setRequestHash(key.getRequestHash());
            return 1;
        });
        when(idempotencyKeyMapper.updateById(any(TicketIdempotencyKey.class))).thenAnswer(invocation -> {
            persistedKey.setTicketId(invocation.getArgument(0, TicketIdempotencyKey.class).getTicketId());
            return 1;
        });
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Ticket.class).setId(21L);
            return 1;
        });
        when(ticketMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            Ticket existing = new Ticket();
            existing.setId(21L);
            existing.setTenantId(3L);
            existing.setRequesterId(7L);
            existing.setTitle("重复提交测试");
            existing.setDescription("同一个请求");
            existing.setPriority(TicketPriority.MEDIUM);
            existing.setStatus(TicketStatus.OPEN);
            return existing;
        });
        when(slaPolicyService.dueAt(eq(3L), eq(TicketPriority.MEDIUM), any()))
                .thenAnswer(invocation -> invocation.getArgument(2, java.time.LocalDateTime.class).plusHours(24));

        Ticket first = new Ticket();
        first.setTitle("重复提交测试");
        first.setDescription("同一个请求");
        Ticket second = new Ticket();
        second.setTitle("重复提交测试");
        second.setDescription("同一个请求");

        Ticket created = ticketService.create(first, 7L, 3L, "request-20260920-001");
        Ticket repeated = ticketService.create(second, 7L, 3L, "request-20260920-001");

        assertEquals(created.getId(), repeated.getId());
        verify(ticketMapper, times(1)).insert(any(Ticket.class));
        verify(idempotencyKeyMapper).updateById(any(TicketIdempotencyKey.class));
    }

    @Test
    void calculatesPriorityBasedSlaWindow() {
        assertEquals(4, SlaPolicy.durationFor(TicketPriority.URGENT).toHours());
        assertEquals(8, SlaPolicy.durationFor(TicketPriority.HIGH).toHours());
        assertEquals(24, SlaPolicy.durationFor(TicketPriority.MEDIUM).toHours());
        assertEquals(72, SlaPolicy.durationFor(TicketPriority.LOW).toHours());
    }

    @Test
    void allowsOnlyValidStateTransitions() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketMapper.updateById(ticket)).thenReturn(1);

        Ticket transitioned = ticketService.transition(1L, TicketStatus.IN_PROGRESS, 3L);

        assertEquals(TicketStatus.IN_PROGRESS, transitioned.getStatus());
        verify(ticketMapper).updateById(ticket);
    }

    @Test
    void rejectsInvalidStateTransitions() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(RuntimeException.class, () -> ticketService.transition(1L, TicketStatus.CLOSED, 3L));

        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    @Test
    void requiresResolutionPackageBeforeWaitingForConfirmation() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(RuntimeException.class,
                () -> ticketService.transition(1L, TicketStatus.WAITING_CONFIRMATION, 9L, 3L));

        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    @Test
    void submitsResolutionAndMovesTicketToConfirmation() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        ticket.setRequesterId(7L);
        ticket.setTicketNo("OPF-RES-001");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketMapper.updateById(ticket)).thenReturn(1);

        Ticket submitted = ticketService.submitResolution(1L, TicketResolutionCode.FIXED,
                "修复 VPN 网关路由配置", "申请人测试访问成功，监控指标恢复", 9L, 3L);

        assertEquals(TicketStatus.WAITING_CONFIRMATION, submitted.getStatus());
        assertEquals(TicketResolutionCode.FIXED, submitted.getResolutionCode());
        assertEquals("修复 VPN 网关路由配置", submitted.getResolutionSummary());
        assertNotNull(submitted.getResolutionSubmittedAt());
        verify(auditLogService).record(eq(3L), eq(9L), eq("TICKET"), eq(1L),
                eq("RESOLUTION_SUBMITTED"), any(), any(), any());
    }

    @Test
    void requesterCanReopenRejectedResolutionWithReason() {
        Ticket ticket = ticket(TicketStatus.WAITING_CONFIRMATION);
        ticket.setRequesterId(7L);
        ticket.setTicketNo("OPF-RES-002");
        ticket.setResolutionSummary("已恢复主要功能");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketMapper.updateById(ticket)).thenReturn(1);

        Ticket reopened = ticketService.decideResolution(1L, TicketResolutionDecision.REOPEN,
                "导出功能仍然报错", 7L, 3L);

        assertEquals(TicketStatus.IN_PROGRESS, reopened.getStatus());
        verify(auditLogService).record(eq(3L), eq(7L), eq("TICKET"), eq(1L),
                eq("RESOLUTION_REOPENED"), any(), any(), eq("申请人反馈仍未解决：导出功能仍然报错"));
    }

    @Test
    void reopenDecisionRequiresReason() {
        Ticket ticket = ticket(TicketStatus.WAITING_CONFIRMATION);
        ticket.setRequesterId(7L);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(RuntimeException.class,
                () -> ticketService.decideResolution(1L, TicketResolutionDecision.REOPEN, " ", 7L, 3L));

        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    @Test
    void onlyOriginalRequesterCanDecideResolution() {
        Ticket ticket = ticket(TicketStatus.WAITING_CONFIRMATION);
        ticket.setRequesterId(7L);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(RuntimeException.class,
                () -> ticketService.decideResolution(1L, TicketResolutionDecision.CONFIRM, null, 8L, 3L));

        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    @Test
    void requestsSatisfactionWhenTicketIsResolved() {
        Ticket ticket = ticket(TicketStatus.WAITING_CONFIRMATION);
        ticket.setRequesterId(7L);
        ticket.setTicketNo("OPF-SAT-001");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketMapper.updateById(ticket)).thenReturn(1);

        Ticket resolved = ticketService.decideResolution(
                1L, TicketResolutionDecision.CONFIRM, null, 7L, 3L);

        assertEquals(TicketStatus.RESOLVED, resolved.getStatus());
        verify(notificationPublisher).enqueue(3L, 7L, 1L,
                "SATISFACTION_REQUESTED", "工单已解决，欢迎评价",
                "工单 OPF-SAT-001 已解决，请对本次服务进行评价。",
                "SATISFACTION_REQUESTED:1", "TICKET", 1L);
    }

    @Test
    void reopeningResolvedTicketClearsCurrentAcceptanceTimestamp() {
        Ticket ticket = ticket(TicketStatus.RESOLVED);
        ticket.setConfirmedAt(java.time.LocalDateTime.now());
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketMapper.updateById(ticket)).thenReturn(1);

        Ticket reopened = ticketService.transition(1L, TicketStatus.IN_PROGRESS, 9L, 3L);

        assertEquals(TicketStatus.IN_PROGRESS, reopened.getStatus());
        assertEquals(null, reopened.getConfirmedAt());
    }

    @Test
    void batchUpdatesSelectedTicketsAndKeepsAuditPerTicket() {
        Ticket first = ticket(TicketStatus.OPEN);
        first.setTicketNo("OPF-FIRST");
        Ticket second = ticket(TicketStatus.OPEN);
        second.setId(2L);
        second.setTicketNo("OPF-SECOND");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(first, second);
        when(ticketMapper.updateById(any(Ticket.class))).thenReturn(1);

        TicketService.BatchUpdateResult result = ticketService.batchUpdate(
                List.of(1L, 2L, 2L), TicketStatus.IN_PROGRESS, TicketPriority.HIGH, 9L, 3L);

        assertEquals(2, result.updated());
        assertEquals(TicketStatus.IN_PROGRESS, first.getStatus());
        assertEquals(TicketPriority.HIGH, second.getPriority());
        verify(ticketMapper, times(2)).updateById(any(Ticket.class));
        verify(auditLogService, times(2)).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void claimsUnassignedTicketAndRecordsAssignmentAudit() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketMapper.updateById(ticket)).thenReturn(1);

        Ticket claimed = ticketService.claim(1L, 9L, 3L);

        assertEquals(9L, claimed.getAssigneeId());
        verify(ticketMapper).updateById(ticket);
        verify(auditLogService).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReleaseByAnotherAgent() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        ticket.setAssigneeId(8L);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(RuntimeException.class, () -> ticketService.release(1L, 9L, false, 3L));

        verify(ticketMapper, never()).updateById(any(Ticket.class));
    }

    @Test
    void rejectsRequesterReadingAnotherRequestersTicket() {
        Ticket ticket = ticket(TicketStatus.OPEN);
        ticket.setRequesterId(7L);
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);

        assertThrows(RuntimeException.class, () -> ticketService.getVisible(1L, 3L, 9L));
    }

    private Ticket ticket(TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setTenantId(3L);
        ticket.setStatus(status);
        ticket.setVersion(0);
        ticket.setPriority(TicketPriority.MEDIUM);
        return ticket;
    }
}
