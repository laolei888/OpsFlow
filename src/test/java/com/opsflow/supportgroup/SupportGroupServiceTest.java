package com.opsflow.supportgroup;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportGroupServiceTest {
    @Mock private SupportGroupMapper groupMapper;
    @Mock private SupportGroupMemberMapper memberMapper;
    @Mock private TicketMapper ticketMapper;
    @Mock private AuthService authService;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private SupportGroupService service;

    @Test
    void createsGroupAndAutomaticallyAddsManagerAsLead() {
        when(authService.isStaffMember(9L, 3L)).thenReturn(true);
        when(groupMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(groupMapper.insert(any(SupportGroup.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SupportGroup.class).setId(41L);
            return 1;
        });
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        SupportGroup result = service.create(new SupportGroupService.GroupCommand(
                "app_support", "应用支持组", "负责核心应用故障与请求", 9L), 7L, 3L);

        assertEquals("APP_SUPPORT", result.getGroupCode());
        assertEquals(9L, result.getManagerId());
        ArgumentCaptor<SupportGroupMember> member = ArgumentCaptor.forClass(SupportGroupMember.class);
        verify(memberMapper).insert(member.capture());
        assertEquals(SupportGroupMemberRole.LEAD, member.getValue().getMemberRole());
        assertEquals(9L, member.getValue().getUserId());
        verify(auditLogService).record(eq(3L), eq(7L), eq("SUPPORT_GROUP"), eq(41L),
                eq("SUPPORT_GROUP_CREATED"), eq(null), eq(result), contains("应用支持组"));
    }

    @Test
    void rejectsDuplicateGroupCodeBeforeInsert() {
        when(groupMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.create(
                new SupportGroupService.GroupCommand("APP_SUPPORT", "应用支持组", null, null), 7L, 3L));

        assertEquals("支持组编码已存在", exception.getMessage());
        verify(groupMapper, never()).insert(any(SupportGroup.class));
    }

    @Test
    void routingClearsAssigneeOutsideTargetGroupAndNotifiesOtherMembers() {
        SupportGroup group = group(true);
        Ticket ticket = ticket();
        ticket.setAssigneeId(11L);
        when(groupMapper.selectForUpdate(41L, 3L)).thenReturn(group);
        when(ticketMapper.selectForUpdate(71L, 3L)).thenReturn(ticket);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(ticketMapper.updateById(ticket)).thenReturn(1);
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(member(7L), member(9L)));

        Ticket result = service.routeTicket(41L, 71L, 7L, 3L);

        assertEquals(41L, result.getAssignmentGroupId());
        assertNull(result.getAssigneeId());
        verify(notificationService).notifyIfAbsent(eq(3L), eq(9L), eq("TICKET_GROUP_ROUTED"),
                eq("支持组收到新工单"), contains("应用支持组"), contains("TICKET_GROUP_ROUTED:71:41"));
        verify(notificationService, never()).notifyIfAbsent(eq(3L), eq(7L), any(), any(), any(), any());
        verify(auditLogService).record(eq(3L), eq(7L), eq("TICKET"), eq(71L),
                eq("TICKET_GROUP_ROUTED"), any(), any(), contains("应用支持组"));
    }

    @Test
    void routingKeepsAssigneeWhoAlreadyBelongsToGroup() {
        Ticket ticket = ticket();
        ticket.setAssigneeId(9L);
        when(groupMapper.selectForUpdate(41L, 3L)).thenReturn(group(true));
        when(ticketMapper.selectForUpdate(71L, 3L)).thenReturn(ticket);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(ticketMapper.updateById(ticket)).thenReturn(1);
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        Ticket result = service.routeTicket(41L, 71L, 7L, 3L);

        assertEquals(9L, result.getAssigneeId());
        assertEquals(41L, result.getAssignmentGroupId());
    }

    @Test
    void disabledGroupCannotReceiveTicket() {
        when(groupMapper.selectForUpdate(41L, 3L)).thenReturn(group(false));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.routeTicket(41L, 71L, 7L, 3L));

        assertEquals("停用的支持组不能接收新工单", exception.getMessage());
        verify(ticketMapper, never()).selectForUpdate(71L, 3L);
    }

    @Test
    void currentManagerCannotBeRemovedFromGroup() {
        when(groupMapper.selectForUpdate(41L, 3L)).thenReturn(group(true));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.removeMember(41L, 9L, 7L, 3L));

        assertEquals("请先更换支持组负责人，再移除该成员", exception.getMessage());
        verify(memberMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void outsiderCannotClaimOrReceiveTicketFromGroupQueue() {
        when(groupMapper.selectOne(any(Wrapper.class))).thenReturn(group(true));
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.requireActiveMember(41L, 88L, 3L));

        assertEquals("该工单属于支持组队列，只能由组内成员领取或被指派", exception.getMessage());
    }

    private SupportGroup group(boolean enabled) {
        SupportGroup group = new SupportGroup();
        group.setId(41L);
        group.setTenantId(3L);
        group.setGroupCode("APP_SUPPORT");
        group.setName("应用支持组");
        group.setManagerId(9L);
        group.setEnabled(enabled);
        group.setCreatedBy(7L);
        group.setCreatedAt(LocalDateTime.now().minusDays(2));
        group.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return group;
    }

    private SupportGroupMember member(Long userId) {
        SupportGroupMember member = new SupportGroupMember();
        member.setId(userId + 100);
        member.setTenantId(3L);
        member.setGroupId(41L);
        member.setUserId(userId);
        member.setMemberRole(SupportGroupMemberRole.MEMBER);
        member.setActive(true);
        member.setJoinedAt(LocalDateTime.now().minusDays(1));
        return member;
    }

    private Ticket ticket() {
        Ticket ticket = new Ticket();
        ticket.setId(71L);
        ticket.setTicketNo("OPF-000071");
        ticket.setTenantId(3L);
        ticket.setRequesterId(5L);
        ticket.setTitle("订单接口大量超时");
        ticket.setPriority(TicketPriority.URGENT);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setVersion(0);
        ticket.setCreatedAt(LocalDateTime.now().minusHours(2));
        ticket.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return ticket;
    }
}
