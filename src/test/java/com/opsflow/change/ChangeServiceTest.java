package com.opsflow.change;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.cmdb.AssetStatus;
import com.opsflow.cmdb.CmdbAsset;
import com.opsflow.cmdb.CmdbAssetMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
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
class ChangeServiceTest {
    @Mock
    private ChangeRequestMapper changeMapper;
    @Mock
    private ChangeTaskMapper taskMapper;
    @Mock
    private ChangeAssetLinkMapper assetLinkMapper;
    @Mock
    private CmdbAssetMapper assetMapper;
    @Mock
    private TicketService ticketService;
    @Mock
    private AuthService authService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ChangeService service;

    @Test
    void standardLowRiskChangeIsAutoApprovedAndCreatesChecklist() {
        when(changeMapper.insert(any(ChangeRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChangeRequest.class).setId(31L);
            return 1;
        });
        when(taskMapper.insert(any(ChangeTask.class))).thenReturn(1);

        ChangeRequest result = service.create(command(ChangeType.STANDARD, ChangeRisk.LOW,
                List.of("执行变更", "验证结果")), "change-key-1", 7L, 7L, 3L);

        assertEquals(ChangeStatus.APPROVED, result.getStatus());
        verify(taskMapper, org.mockito.Mockito.times(2)).insert(any(ChangeTask.class));
        verify(notificationService).notifyIfAbsent(eq(3L), eq(7L), eq("CHANGE_APPROVED"),
                contains("预批准"), contains("标准低风险"), eq("CHANGE_APPROVED:31"));
        verify(authService, never()).staffMembers(3L);
    }

    @Test
    void normalHighRiskChangeWaitsForAdminApproval() {
        when(changeMapper.insert(any(ChangeRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChangeRequest.class).setId(32L);
            return 1;
        });
        when(authService.staffMembers(3L)).thenReturn(List.of(
                new StaffSummary(9L, "admin", "管理员", List.of("ADMIN"))));

        ChangeRequest result = service.create(command(ChangeType.NORMAL, ChangeRisk.HIGH, List.of()),
                null, 7L, 7L, 3L);

        assertEquals(ChangeStatus.PENDING_APPROVAL, result.getStatus());
        verify(notificationService).notifyIfAbsent(eq(3L), eq(9L), eq("CHANGE_APPROVAL_REQUIRED"),
                contains("待审批"), contains("数据库升级"), eq("CHANGE_APPROVAL_REQUIRED:32"));
    }

    @Test
    void approvalLocksPendingChangeAndNotifiesRequester() {
        ChangeRequest change = change(31L, ChangeStatus.PENDING_APPROVAL);
        when(changeMapper.selectForUpdate(31L, 3L)).thenReturn(change);
        when(changeMapper.updateById(change)).thenReturn(1);

        ChangeRequest result = service.approve(31L, "风险已评估", 9L, 3L);

        assertEquals(ChangeStatus.APPROVED, result.getStatus());
        assertEquals(9L, result.getDecidedBy());
        verify(notificationService).notifyIfAbsent(eq(3L), eq(7L), eq("CHANGE_APPROVED"),
                contains("已通过"), contains("数据库升级"), eq("CHANGE_APPROVED:31"));
    }

    @Test
    void completionIsBlockedWhenChecklistHasUnfinishedTask() {
        when(changeMapper.selectForUpdate(31L, 3L)).thenReturn(change(31L, ChangeStatus.IN_PROGRESS));
        when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.transition(31L, ChangeStatus.COMPLETED, 9L, 3L));
        verify(changeMapper, never()).updateById(any(ChangeRequest.class));
    }

    @Test
    void retiredAssetCannotBeLinkedToChange() {
        when(changeMapper.selectOne(any(Wrapper.class))).thenReturn(change(31L, ChangeStatus.APPROVED));
        CmdbAsset asset = new CmdbAsset();
        asset.setId(88L);
        asset.setTenantId(3L);
        asset.setStatus(AssetStatus.RETIRED);
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(asset);

        assertThrows(BusinessException.class, () -> service.linkAsset(31L,
                new ChangeService.AssetLinkCommand(88L, "AFFECTED", "数据库节点"), 3L, 9L));
        verify(assetLinkMapper, never()).insert(any(ChangeAssetLink.class));
    }

    @Test
    void rejectionRequiresReasonBeforeLoadingChange() {
        assertThrows(BusinessException.class, () -> service.reject(31L, "  ", 9L, 3L));
        verify(changeMapper, never()).selectForUpdate(any(), any());
    }

    private ChangeService.ChangeCommand command(ChangeType type, ChangeRisk risk, List<String> tasks) {
        return new ChangeService.ChangeCommand("数据库升级", "升级订单库版本并观察业务指标", type, risk,
                "订单服务和数据库集群", "执行备份，逐节点升级并观察连接池", "验证订单创建、查询和监控指标",
                "停止升级并恢复备份，回滚应用配置", null, null, null, tasks);
    }

    private ChangeRequest change(Long id, ChangeStatus status) {
        ChangeRequest change = new ChangeRequest();
        change.setId(id);
        change.setTenantId(3L);
        change.setRequesterId(7L);
        change.setTitle("数据库升级");
        change.setStatus(status);
        return change;
    }
}
