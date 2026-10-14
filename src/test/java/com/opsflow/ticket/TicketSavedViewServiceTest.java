package com.opsflow.ticket;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketSavedViewServiceTest {
    @Mock
    private TicketSavedViewMapper viewMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TicketSavedViewService savedViewService;

    @Test
    void createsTeamViewAndRecordsAudit() {
        when(viewMapper.selectOne(any())).thenReturn(null);

        TicketSavedViewService.TicketSavedViewView result = savedViewService.create(
                new TicketSavedViewService.SavedViewCommand(
                        "逾期高优先级", "TEAM", TicketStatus.IN_PROGRESS,
                        TicketPriority.HIGH, "VPN", true, false),
                3L, 7L, true);

        ArgumentCaptor<TicketSavedView> captor = ArgumentCaptor.forClass(TicketSavedView.class);
        verify(viewMapper).insert(captor.capture());
        TicketSavedView saved = captor.getValue();
        assertEquals(3L, saved.getTenantId());
        assertEquals(7L, saved.getOwnerId());
        assertEquals("TEAM", saved.getVisibility());
        assertEquals(TicketStatus.IN_PROGRESS, saved.getStatus());
        assertEquals(TicketPriority.HIGH, saved.getPriority());
        assertEquals("VPN", saved.getKeyword());
        assertEquals("逾期高优先级", result.name());
        verify(auditLogService).record(eq(3L), eq(7L), eq("TICKET_SAVED_VIEW"),
                eq(null), eq("SAVED_VIEW_CREATED"), eq(null), any(), eq("创建工单保存视图"));
    }

    @Test
    void requesterCannotCreateTeamView() {
        assertThrows(BusinessException.class, () -> savedViewService.create(
                new TicketSavedViewService.SavedViewCommand(
                        "团队视图", "TEAM", null, null, null, false, false),
                3L, 7L, false));
    }

    @Test
    void listForStaffIncludesOwnAndTeamViews() {
        TicketSavedView own = view(1L, 3L, 7L, "我的队列", "PRIVATE");
        TicketSavedView team = view(2L, 3L, 8L, "团队逾期", "TEAM");
        when(viewMapper.selectList(any())).thenReturn(List.of(own, team));

        List<TicketSavedViewService.TicketSavedViewView> result =
                savedViewService.list(3L, 7L, true);

        assertEquals(2, result.size());
        assertEquals("我的队列", result.get(0).name());
        assertEquals("TEAM", result.get(1).visibility());
    }

    @Test
    void rejectsDuplicateOwnedName() {
        when(viewMapper.selectOne(any())).thenReturn(view(1L, 3L, 7L, "已有视图", "PRIVATE"));

        assertThrows(BusinessException.class, () -> savedViewService.create(
                new TicketSavedViewService.SavedViewCommand(
                        "已有视图", "PRIVATE", null, null, null, false, false),
                3L, 7L, true));
    }

    @Test
    void updatesOwnedViewAndRecordsAudit() {
        TicketSavedView existing = view(1L, 3L, 7L, "我的队列", "PRIVATE");
        existing.setSort(TicketSort.CREATED_DESC);
        when(viewMapper.selectOne(any())).thenReturn(existing, (TicketSavedView) null);

        TicketSavedViewService.TicketSavedViewView result = savedViewService.update(
                1L,
                new TicketSavedViewService.SavedViewCommand(
                        "更新后的队列", "PRIVATE", TicketStatus.OPEN,
                        TicketPriority.URGENT, "VPN", true, true, TicketSort.SLA_ASC),
                3L, 7L, true);

        verify(viewMapper).updateById(existing);
        assertEquals("更新后的队列", result.name());
        assertEquals(TicketSort.SLA_ASC, result.sort());
        assertEquals(TicketStatus.OPEN, existing.getStatus());
        assertEquals(TicketPriority.URGENT, existing.getPriority());
        assertEquals("VPN", existing.getKeyword());
        verify(auditLogService).record(eq(3L), eq(7L), eq("TICKET_SAVED_VIEW"),
                eq(1L), eq("SAVED_VIEW_UPDATED"), any(), any(), eq("更新工单保存视图"));
    }
    private TicketSavedView view(Long id, Long tenantId, Long ownerId, String name, String visibility) {
        TicketSavedView view = new TicketSavedView();
        view.setId(id);
        view.setTenantId(tenantId);
        view.setOwnerId(ownerId);
        view.setName(name);
        view.setVisibility(visibility);
        view.setOverdue(false);
        view.setMine(false);
        return view;
    }
}
