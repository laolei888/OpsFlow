package com.opsflow.problem;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemServiceTest {
    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private ProblemTaskMapper taskMapper;
    @Mock
    private ProblemTicketLinkMapper ticketLinkMapper;
    @Mock
    private ProblemChangeLinkMapper changeLinkMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private ChangeRequestMapper changeMapper;
    @Mock
    private TicketService ticketService;
    @Mock
    private AuthService authService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ProblemService service;

    @Test
    void createsKnownErrorProblemAndNotifiesAdmin() {
        when(problemMapper.insert(any(Problem.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Problem.class).setId(41L);
            return 1;
        });
        when(taskMapper.insert(any(ProblemTask.class))).thenReturn(1);
        when(authService.staffMembers(3L)).thenReturn(List.of(
                new StaffSummary(9L, "admin", "管理员", List.of("ADMIN"))));

        Problem result = service.create(command(true, List.of("收集错误日志", "复现故障")),
                "problem-key-1", 7L, 3L);

        assertEquals(ProblemStatus.KNOWN_ERROR, result.getStatus());
        assertEquals(Boolean.TRUE, result.getKnownError());
        verify(taskMapper, org.mockito.Mockito.times(2)).insert(any(ProblemTask.class));
        verify(notificationService).notifyIfAbsent(eq(3L), eq(9L), eq("PROBLEM_CREATED"),
                contains("问题单"), contains("根因分析"), eq("PROBLEM_CREATED:41"));
    }

    @Test
    void transitionToResolvedRequiresAllAnalysisTasksDone() {
        when(problemMapper.selectForUpdate(41L, 3L)).thenReturn(problem(41L, ProblemStatus.INVESTIGATING));
        when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.transition(41L, ProblemStatus.RESOLVED, 9L, 3L));
        verify(problemMapper, never()).updateById(any(Problem.class));
    }

    @Test
    void linksVisibleTicketToProblemIdempotently() {
        Problem problem = problem(41L, ProblemStatus.INVESTIGATING);
        when(problemMapper.selectOne(any(Wrapper.class))).thenReturn(problem);
        Ticket ticket = new Ticket();
        ticket.setId(88L);
        ticket.setTenantId(3L);
        ticket.setTicketNo("OPF-TEST0088");
        ticket.setTitle("数据库连接失败");
        when(ticketService.getVisible(88L, 3L, null)).thenReturn(ticket);
        when(ticketLinkMapper.insert(any(ProblemTicketLink.class))).thenReturn(1);

        ProblemService.ProblemTicketView view = service.linkTicket(41L,
                new ProblemService.LinkCommand(88L, "SYMPTOM", "同一错误已出现多次"), 3L, 9L);

        assertEquals(88L, view.ticketId());
        assertEquals("SYMPTOM", view.linkType());
        verify(ticketLinkMapper).insert(any(ProblemTicketLink.class));
    }

    @Test
    void linksRemediationChangeOnlyInsideCurrentTenant() {
        Problem problem = problem(41L, ProblemStatus.PENDING_CHANGE);
        when(problemMapper.selectOne(any(Wrapper.class))).thenReturn(problem);
        ChangeRequest change = new ChangeRequest();
        change.setId(31L);
        change.setTenantId(3L);
        change.setTitle("数据库连接池参数调整");
        when(changeMapper.selectOne(any(Wrapper.class))).thenReturn(change);
        when(changeLinkMapper.insert(any(ProblemChangeLink.class))).thenReturn(1);

        ProblemService.ProblemChangeView view = service.linkChange(41L,
                new ProblemService.LinkCommand(31L, "REMEDIATION", "通过变更修复根因"), 3L, 9L);

        assertEquals(31L, view.changeId());
        assertEquals("REMEDIATION", view.linkType());
        verify(changeLinkMapper).insert(any(ProblemChangeLink.class));
    }

    @Test
    void updatesRootCauseAndMarksKnownError() {
        Problem problem = problem(41L, ProblemStatus.INVESTIGATING);
        when(problemMapper.selectForUpdate(41L, 3L)).thenReturn(problem);
        when(problemMapper.updateById(problem)).thenReturn(1);

        Problem result = service.update(41L,
                new ProblemService.ProblemUpdateCommand(ProblemPriority.HIGH, "连接池耗尽导致数据库拒绝连接",
                        "临时重启连接池", null, true, null), 9L, 3L);

        assertEquals(ProblemStatus.KNOWN_ERROR, result.getStatus());
        assertEquals("连接池耗尽导致数据库拒绝连接", result.getRootCause());
        assertEquals(Boolean.TRUE, result.getKnownError());
        verify(auditLogService).record(eq(3L), eq(9L), eq("PROBLEM"), eq(41L),
                eq("PROBLEM_UPDATED"), any(), eq(result), contains("分析"));
    }

    @Test
    void closedProblemCannotBeChanged() {
        when(problemMapper.selectForUpdate(41L, 3L)).thenReturn(problem(41L, ProblemStatus.CLOSED));

        assertThrows(BusinessException.class, () -> service.update(41L,
                new ProblemService.ProblemUpdateCommand(ProblemPriority.LOW, "新的根因", null, null, null, null),
                9L, 3L));
    }

    private ProblemService.ProblemCommand command(boolean knownError, List<String> tasks) {
        return new ProblemService.ProblemCommand("订单数据库连接反复失败",
                "过去一周多次出现订单查询失败，多个工单报告相同错误。",
                "订单查询和客服工作台间歇性不可用", ProblemPriority.HIGH,
                "临时切换只读副本并重启连接池", knownError, null, tasks);
    }

    private Problem problem(Long id, ProblemStatus status) {
        Problem problem = new Problem();
        problem.setId(id);
        problem.setTenantId(3L);
        problem.setRequesterId(7L);
        problem.setTitle("订单数据库连接反复失败");
        problem.setDescription("重复出现数据库连接错误");
        problem.setPriority(ProblemPriority.HIGH);
        problem.setStatus(status);
        problem.setKnownError(false);
        return problem;
    }
}
