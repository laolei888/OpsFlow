package com.opsflow.release;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.change.ChangeAssetLink;
import com.opsflow.change.ChangeAssetLinkMapper;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.change.ChangeStatus;
import com.opsflow.common.BusinessException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseServiceTest {
    @Mock
    private ReleasePlanMapper releaseMapper;
    @Mock
    private ReleaseChangeLinkMapper changeLinkMapper;
    @Mock
    private ReleaseChecklistItemMapper checklistMapper;
    @Mock
    private ReleaseFreezeWindowMapper freezeMapper;
    @Mock
    private ChangeRequestMapper changeMapper;
    @Mock
    private ChangeAssetLinkMapper changeAssetLinkMapper;
    @Mock
    private AuthService authService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ReleaseService service;

    @Test
    void goDecisionRequiresApprovedChangesAndPassedPrecheck() {
        ReleasePlan release = release(ReleaseStatus.DRAFT, ReleaseGateDecision.PENDING);
        when(releaseMapper.selectForUpdate(41L, 3L)).thenReturn(release);
        when(changeLinkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(link(81L)));
        when(changeMapper.selectOne(any(Wrapper.class))).thenReturn(change(ChangeStatus.APPROVED));
        when(checklistMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                checklist(ReleaseChecklistPhase.PRECHECK, ReleaseChecklistStatus.PASSED),
                checklist(ReleaseChecklistPhase.PRECHECK, ReleaseChecklistStatus.PASSED),
                checklist(ReleaseChecklistPhase.PRECHECK, ReleaseChecklistStatus.PASSED)));

        ReleasePlan result = service.decideGoNoGo(41L, new ReleaseService.GoNoGoCommand(true, "监控与回滚方案已复核"), 3L, 9L);

        assertEquals(ReleaseStatus.READY, result.getStatus());
        assertEquals(ReleaseGateDecision.GO, result.getGoNoGoStatus());
        verify(releaseMapper).updateById(release);
    }

    @Test
    void startIsBlockedWhenPrecheckIsStillPending() {
        ReleasePlan release = release(ReleaseStatus.READY, ReleaseGateDecision.GO);
        when(releaseMapper.selectForUpdate(41L, 3L)).thenReturn(release);
        when(checklistMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                checklist(ReleaseChecklistPhase.PRECHECK, ReleaseChecklistStatus.PENDING)));

        assertThrows(BusinessException.class, () -> service.start(41L, 3L, 9L));
        verify(releaseMapper, never()).updateById(any(ReleasePlan.class));
    }

    @Test
    void verificationIsBlockedUntilEveryRequiredGatePasses() {
        ReleasePlan release = release(ReleaseStatus.IN_PROGRESS, ReleaseGateDecision.GO);
        when(releaseMapper.selectForUpdate(41L, 3L)).thenReturn(release);
        when(checklistMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                checklist(ReleaseChecklistPhase.PRECHECK, ReleaseChecklistStatus.PASSED),
                checklist(ReleaseChecklistPhase.DEPLOYMENT, ReleaseChecklistStatus.PASSED),
                checklist(ReleaseChecklistPhase.VERIFICATION, ReleaseChecklistStatus.PENDING)));

        assertThrows(BusinessException.class, () -> service.verify(41L, "观察中", 3L, 9L));
        verify(releaseMapper, never()).updateById(any(ReleasePlan.class));
    }

    @Test
    void nonApprovedChangeCannotJoinReleaseScope() {
        ReleasePlan release = release(ReleaseStatus.DRAFT, ReleaseGateDecision.PENDING);
        when(releaseMapper.selectForUpdate(41L, 3L)).thenReturn(release);
        when(changeLinkMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(changeMapper.selectOne(any(Wrapper.class))).thenReturn(change(ChangeStatus.PENDING_APPROVAL));

        assertThrows(BusinessException.class, () -> service.linkChange(41L, 81L, 10, 3L, 9L));
        verify(changeLinkMapper, never()).insert(any(ReleaseChangeLink.class));
    }

    @Test
    void normalReleaseIsBlockedWhenItOverlapsAnEnabledFreeze() {
        ReleasePlan release = release(ReleaseStatus.DRAFT, ReleaseGateDecision.PENDING);
        when(releaseMapper.selectForUpdate(41L, 3L)).thenReturn(release);
        when(changeLinkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(link(81L)));
        when(changeMapper.selectOne(any(Wrapper.class))).thenReturn(change(ChangeStatus.APPROVED));
        when(checklistMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                checklist(ReleaseChecklistPhase.PRECHECK, ReleaseChecklistStatus.PASSED)));
        when(freezeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(freeze()));

        assertThrows(BusinessException.class,
                () -> service.decideGoNoGo(41L, new ReleaseService.GoNoGoCommand(true, "准备上线"), 3L, 9L));
        verify(releaseMapper, never()).updateById(any(ReleasePlan.class));
    }

    @Test
    void readyReleaseWithSharedAffectedAssetBlocksGoDecision() {
        ReleasePlan release = release(ReleaseStatus.DRAFT, ReleaseGateDecision.PENDING);
        ReleasePlan colliding = release(ReleaseStatus.READY, ReleaseGateDecision.GO);
        colliding.setId(42L);
        colliding.setReleaseNo("REL-2026B");
        when(releaseMapper.selectForUpdate(41L, 3L)).thenReturn(release);
        when(changeLinkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(link(81L)));
        when(changeMapper.selectOne(any(Wrapper.class))).thenReturn(change(ChangeStatus.APPROVED));
        when(checklistMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                checklist(ReleaseChecklistPhase.PRECHECK, ReleaseChecklistStatus.PASSED)));
        when(freezeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(changeAssetLinkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(assetLink(501L)));
        when(releaseMapper.selectList(any(Wrapper.class))).thenReturn(List.of(colliding));

        assertThrows(BusinessException.class,
                () -> service.decideGoNoGo(41L, new ReleaseService.GoNoGoCommand(true, "准备上线"), 3L, 9L));
        verify(releaseMapper, never()).updateById(any(ReleasePlan.class));
    }

    private ReleasePlan release(ReleaseStatus status, ReleaseGateDecision decision) {
        ReleasePlan release = new ReleasePlan();
        release.setId(41L);
        release.setTenantId(3L);
        release.setReleaseNo("REL-2026A");
        release.setName("订单服务 9 月发布");
        release.setEnvironment(ReleaseEnvironment.PRODUCTION);
        release.setReleaseManagerId(9L);
        release.setPlannedStart(LocalDateTime.now().plusHours(1));
        release.setPlannedEnd(LocalDateTime.now().plusHours(2));
        release.setStatus(status);
        release.setGoNoGoStatus(decision);
        return release;
    }

    private ReleaseChangeLink link(Long changeId) {
        ReleaseChangeLink link = new ReleaseChangeLink();
        link.setId(91L);
        link.setTenantId(3L);
        link.setReleaseId(41L);
        link.setChangeId(changeId);
        return link;
    }

    private ChangeRequest change(ChangeStatus status) {
        ChangeRequest change = new ChangeRequest();
        change.setId(81L);
        change.setTenantId(3L);
        change.setStatus(status);
        change.setChangeType(com.opsflow.change.ChangeType.NORMAL);
        return change;
    }

    private ReleaseFreezeWindow freeze() {
        ReleaseFreezeWindow freeze = new ReleaseFreezeWindow();
        freeze.setId(71L);
        freeze.setTenantId(3L);
        freeze.setName("国庆生产冻结");
        freeze.setEnvironment(ReleaseEnvironment.PRODUCTION);
        freeze.setStartsAt(LocalDateTime.now().minusMinutes(5));
        freeze.setEndsAt(LocalDateTime.now().plusHours(4));
        freeze.setReason("大促期间只允许紧急变更");
        freeze.setEnabled(true);
        return freeze;
    }

    private ChangeAssetLink assetLink(Long assetId) {
        ChangeAssetLink link = new ChangeAssetLink();
        link.setTenantId(3L);
        link.setChangeId(81L);
        link.setAssetId(assetId);
        return link;
    }

    private ReleaseChecklistItem checklist(ReleaseChecklistPhase phase, ReleaseChecklistStatus status) {
        ReleaseChecklistItem item = new ReleaseChecklistItem();
        item.setTenantId(3L);
        item.setReleaseId(41L);
        item.setPhase(phase);
        item.setRequiredItem(true);
        item.setStatus(status);
        return item;
    }
}
