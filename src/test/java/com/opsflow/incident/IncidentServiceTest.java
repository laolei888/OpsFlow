package com.opsflow.incident;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.cmdb.CmdbAssetMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
import com.opsflow.oncall.OnCallEscalationService;
import com.opsflow.postmortem.IncidentPostmortemMapper;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {
    @Mock
    private IncidentMapper incidentMapper;
    @Mock
    private IncidentUpdateMapper updateMapper;
    @Mock
    private IncidentTaskMapper taskMapper;
    @Mock
    private IncidentTicketLinkMapper ticketLinkMapper;
    @Mock
    private IncidentAssetLinkMapper assetLinkMapper;
    @Mock
    private IncidentPostmortemMapper postmortemMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private CmdbAssetMapper assetMapper;
    @Mock
    private AuthService authService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private OnCallEscalationService onCallEscalationService;

    @InjectMocks
    private IncidentService service;

    @Test
    void declaresSevOneIncidentWithTimelineTasksAndStaffNotifications() {
        when(authService.isStaffMember(7L, 3L)).thenReturn(true);
        when(incidentMapper.insert(any(Incident.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Incident.class).setId(61L);
            return 1;
        });
        when(authService.staffMembers(3L)).thenReturn(List.of(
                new StaffSummary(7L, "commander", "事件指挥", List.of("AGENT")),
                new StaffSummary(8L, "oncall", "值班工程师", List.of("AGENT"))));

        Incident result = service.create(command(IncidentSeverity.SEV1, List.of("确认影响范围", "切换流量")),
                "incident-key-1", 7L, 3L);

        assertEquals(IncidentStatus.DECLARED, result.getStatus());
        assertEquals(7L, result.getCommanderId());
        assertNotNull(result.getStartedAt());
        verify(updateMapper).insert(any(IncidentUpdate.class));
        verify(taskMapper, times(2)).insert(any(IncidentTask.class));
        verify(notificationService).notifyIfAbsent(eq(3L), eq(8L), eq("INCIDENT_DECLARED"),
                eq("重大事件已宣布"), contains("SEV1"), eq("INCIDENT_DECLARED:61:8"));
        verify(auditLogService).record(eq(3L), eq(7L), eq("INCIDENT"), eq(61L),
                eq("INCIDENT_DECLARED"), eq(null), eq(result), contains("宣布"));
    }

    @Test
    void closingIncidentRequiresPostmortem() {
        when(incidentMapper.selectForUpdate(61L, 3L)).thenReturn(incident(61L, IncidentStatus.RESOLVED, null));

        assertThrows(BusinessException.class, () -> service.transition(61L, IncidentStatus.CLOSED, 7L, 3L));

        verify(incidentMapper, never()).updateById(any(Incident.class));
    }

    @Test
    void closingIncidentRequiresEveryCollaborationTaskDone() {
        when(incidentMapper.selectForUpdate(61L, 3L)).thenReturn(incident(61L, IncidentStatus.RESOLVED, "已完成复盘"));
        when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.transition(61L, IncidentStatus.CLOSED, 7L, 3L));

        verify(incidentMapper, never()).updateById(any(Incident.class));
    }

    @Test
    void closesIncidentAfterPostmortemAndTasksAreComplete() {
        Incident incident = incident(61L, IncidentStatus.RESOLVED, "根因已修复，并补充了告警与容量检查。");
        when(incidentMapper.selectForUpdate(61L, 3L)).thenReturn(incident);
        when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(incidentMapper.updateById(any(Incident.class))).thenReturn(1);

        Incident result = service.transition(61L, IncidentStatus.CLOSED, 7L, 3L);

        assertEquals(IncidentStatus.CLOSED, result.getStatus());
        assertNotNull(result.getClosedAt());
        verify(updateMapper).insert(any(IncidentUpdate.class));
        verify(auditLogService).record(eq(3L), eq(7L), eq("INCIDENT"), eq(61L),
                eq("INCIDENT_STATUS_CHANGED"), any(), any(), contains("状态流转"));
    }

    @Test
    void approvedStructuredPostmortemAlsoAllowsClosingIncident() {
        Incident incident = incident(61L, IncidentStatus.RESOLVED, null);
        when(incidentMapper.selectForUpdate(61L, 3L)).thenReturn(incident);
        when(postmortemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(incidentMapper.updateById(any(Incident.class))).thenReturn(1);

        Incident result = service.transition(61L, IncidentStatus.CLOSED, 7L, 3L);

        assertEquals(IncidentStatus.CLOSED, result.getStatus());
        verify(incidentMapper).updateById(incident);
    }

    @Test
    void linksTicketInsideCurrentTenantUsingDefaultSymptomType() {
        when(incidentMapper.selectOne(any(Wrapper.class))).thenReturn(incident(61L, IncidentStatus.INVESTIGATING, null));
        Ticket ticket = new Ticket();
        ticket.setId(88L);
        ticket.setTenantId(3L);
        ticket.setTicketNo("OPF-000088");
        ticket.setTitle("订单服务超时");
        when(ticketMapper.selectOne(any(Wrapper.class))).thenReturn(ticket);
        when(ticketLinkMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        IncidentService.IncidentTicketView result = service.linkTicket(61L,
                new IncidentService.LinkCommand(88L, null, "同一事件的症状工单"), 7L, 3L);

        assertEquals(88L, result.ticketId());
        assertEquals("SYMPTOM", result.linkType());
        verify(ticketLinkMapper).insert(any(IncidentTicketLink.class));
        verify(auditLogService).record(eq(3L), eq(7L), eq("INCIDENT"), eq(61L),
                eq("INCIDENT_TICKET_LINKED"), eq(null), eq(result), contains("关联"));
    }

    private IncidentService.IncidentCommand command(IncidentSeverity severity, List<String> tasks) {
        return new IncidentService.IncidentCommand("订单服务大面积超时", "订单创建与查询接口在生产环境持续超时。",
                severity, null, "下单失败，客服查询受阻", "部分用户无法完成下单", "#inc-order", tasks);
    }

    private Incident incident(Long id, IncidentStatus status, String postmortem) {
        Incident incident = new Incident();
        incident.setId(id);
        incident.setTenantId(3L);
        incident.setIncidentNo("INC-TEST61");
        incident.setTitle("订单服务大面积超时");
        incident.setSummary("订单创建与查询接口在生产环境持续超时。");
        incident.setSeverity(IncidentSeverity.SEV1);
        incident.setStatus(status);
        incident.setCommanderId(7L);
        incident.setDeclaredBy(7L);
        incident.setPostmortem(postmortem);
        return incident;
    }
}
