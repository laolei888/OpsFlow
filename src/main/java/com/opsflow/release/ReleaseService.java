package com.opsflow.release;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.change.ChangeAssetLink;
import com.opsflow.change.ChangeAssetLinkMapper;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.change.ChangeStatus;
import com.opsflow.change.ChangeType;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A release is a governed deployment window that groups approved changes.
 * Change execution remains owned by the change module; this service only
 * records release readiness, evidence and the operator's Go/No-Go decision.
 */
@Service
@RequiredArgsConstructor
public class ReleaseService {
    private static final List<ChecklistSeed> DEFAULT_CHECKLIST = List.of(
            new ChecklistSeed(ReleaseChecklistPhase.PRECHECK, "已确认所有关联变更均已获批", true, 10),
            new ChecklistSeed(ReleaseChecklistPhase.PRECHECK, "已复核回滚方案、备份与责任人", true, 20),
            new ChecklistSeed(ReleaseChecklistPhase.PRECHECK, "已确认实施窗口、值班与沟通计划", true, 30),
            new ChecklistSeed(ReleaseChecklistPhase.DEPLOYMENT, "已记录部署执行结果与异常", true, 10),
            new ChecklistSeed(ReleaseChecklistPhase.VERIFICATION, "已完成核心链路与业务验收", true, 10),
            new ChecklistSeed(ReleaseChecklistPhase.VERIFICATION, "已观察监控指标且无新增严重告警", true, 20)
    );

    private final ReleasePlanMapper releaseMapper;
    private final ReleaseChangeLinkMapper changeLinkMapper;
    private final ReleaseChecklistItemMapper checklistMapper;
    private final ReleaseFreezeWindowMapper freezeMapper;
    private final ChangeRequestMapper changeMapper;
    private final ChangeAssetLinkMapper changeAssetLinkMapper;
    private final AuthService authService;
    private final AuditLogService auditLogService;

    public ReleaseCenterView center(Long tenantId) {
        List<ReleasePlan> releases = releaseMapper.selectList(new LambdaQueryWrapper<ReleasePlan>()
                .eq(ReleasePlan::getTenantId, tenantId)
                .orderByAsc(ReleasePlan::getStatus)
                .orderByDesc(ReleasePlan::getPlannedStart)
                .orderByDesc(ReleasePlan::getId)
                .last("LIMIT 120"));
        List<ReleaseView> views = releases.stream().map(release -> new ReleaseView(release,
                gateSummary(release.getId(), tenantId), linkedChangeCount(release.getId(), tenantId),
                conflictsFor(release, tenantId).size(), freezeOverlaps(release, tenantId).size())).toList();
        List<ChangeRequest> availableChanges = changeMapper.selectList(new LambdaQueryWrapper<ChangeRequest>()
                .eq(ChangeRequest::getTenantId, tenantId)
                .eq(ChangeRequest::getStatus, ChangeStatus.APPROVED)
                .orderByAsc(ChangeRequest::getScheduledStart)
                .orderByDesc(ChangeRequest::getUpdatedAt)
                .last("LIMIT 200"));
        return new ReleaseCenterView(views, availableChanges, freezeWindows(tenantId));
    }

    public ReleaseDetail detail(Long id, Long tenantId) {
        ReleasePlan release = getRelease(id, tenantId);
        List<ReleaseChangeLink> links = changeLinks(id, tenantId);
        Map<Long, ChangeRequest> changes = new HashMap<>();
        for (ReleaseChangeLink link : links) {
            ChangeRequest change = findChange(link.getChangeId(), tenantId);
            if (change != null) changes.put(change.getId(), change);
        }
        List<ReleaseChangeView> changeViews = links.stream()
                .map(link -> new ReleaseChangeView(link, changes.get(link.getChangeId())))
                .toList();
        return new ReleaseDetail(release, gateSummary(id, tenantId), changeViews, checklist(id, tenantId),
                conflictsFor(release, tenantId), freezeOverlaps(release, tenantId));
    }

    public ReleaseCalendarView calendar(Long tenantId, LocalDateTime startsAt, LocalDateTime endsAt) {
        LocalDateTime start = startsAt == null ? LocalDateTime.now().minusDays(7) : startsAt;
        LocalDateTime end = endsAt == null ? LocalDateTime.now().plusDays(60) : endsAt;
        if (!end.isAfter(start)) throw new BusinessException("日历结束时间必须晚于开始时间");
        if (end.isAfter(start.plusDays(180))) throw new BusinessException("单次日历查询最多 180 天");
        List<ReleasePlan> releases = releaseMapper.selectList(new LambdaQueryWrapper<ReleasePlan>()
                        .eq(ReleasePlan::getTenantId, tenantId)
                        .isNotNull(ReleasePlan::getPlannedStart)
                        .isNotNull(ReleasePlan::getPlannedEnd)
                        .orderByAsc(ReleasePlan::getPlannedStart)
                        .last("LIMIT 300"))
                .stream().filter(release -> windowsOverlap(release.getPlannedStart(), release.getPlannedEnd(), start, end))
                .toList();
        List<ReleaseFreezeWindow> freezes = freezeWindows(tenantId).stream()
                .filter(freeze -> windowsOverlap(freeze.getStartsAt(), freeze.getEndsAt(), start, end))
                .toList();
        return new ReleaseCalendarView(start, end, releases, freezes);
    }

    public List<ReleaseFreezeWindow> freezeWindows(Long tenantId) {
        return freezeMapper.selectList(new LambdaQueryWrapper<ReleaseFreezeWindow>()
                .eq(ReleaseFreezeWindow::getTenantId, tenantId)
                .orderByDesc(ReleaseFreezeWindow::getEnabled)
                .orderByAsc(ReleaseFreezeWindow::getStartsAt)
                .last("LIMIT 120"));
    }

    @Transactional
    public ReleaseFreezeWindow createFreeze(FreezeWindowCommand command, Long tenantId, Long actorId) {
        validateFreeze(command);
        LocalDateTime now = LocalDateTime.now();
        ReleaseFreezeWindow freeze = new ReleaseFreezeWindow();
        applyFreeze(freeze, command);
        freeze.setTenantId(tenantId);
        freeze.setCreatedBy(actorId);
        freeze.setCreatedAt(now);
        freeze.setUpdatedAt(now);
        freezeMapper.insert(freeze);
        auditLogService.record(tenantId, actorId, "RELEASE_FREEZE_WINDOW", freeze.getId(), "RELEASE_FREEZE_CREATED",
                null, freeze, "创建发布冻结窗口");
        return freeze;
    }

    @Transactional
    public ReleaseFreezeWindow updateFreeze(Long id, FreezeWindowCommand command, Long tenantId, Long actorId) {
        validateFreeze(command);
        ReleaseFreezeWindow freeze = getFreeze(id, tenantId);
        ReleaseFreezeWindow before = copyFreeze(freeze);
        applyFreeze(freeze, command);
        freeze.setUpdatedAt(LocalDateTime.now());
        freezeMapper.updateById(freeze);
        auditLogService.record(tenantId, actorId, "RELEASE_FREEZE_WINDOW", id, "RELEASE_FREEZE_UPDATED",
                before, freeze, "更新发布冻结窗口");
        return freeze;
    }

    @Transactional
    public void deleteFreeze(Long id, Long tenantId, Long actorId) {
        ReleaseFreezeWindow freeze = getFreeze(id, tenantId);
        freezeMapper.deleteById(id);
        auditLogService.record(tenantId, actorId, "RELEASE_FREEZE_WINDOW", id, "RELEASE_FREEZE_DELETED",
                freeze, null, "删除发布冻结窗口");
    }

    @Transactional
    public ReleasePlan create(ReleaseCommand command, Long tenantId, Long actorId) {
        validateCommand(command, tenantId, actorId);
        LocalDateTime now = LocalDateTime.now();
        ReleasePlan release = new ReleasePlan();
        applyCommand(release, command, tenantId, actorId);
        release.setReleaseNo("REL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        release.setStatus(ReleaseStatus.DRAFT);
        release.setGoNoGoStatus(ReleaseGateDecision.PENDING);
        release.setCreatedBy(actorId);
        release.setCreatedAt(now);
        release.setUpdatedAt(now);
        releaseMapper.insert(release);
        seedChecklist(release.getId(), tenantId, actorId, now);
        if (command.changeIds() != null) {
            Set<Long> uniqueIds = new HashSet<>(command.changeIds());
            if (uniqueIds.size() != command.changeIds().size()) throw new BusinessException("同一变更不能重复加入发布单");
            int order = 10;
            for (Long changeId : command.changeIds()) {
                linkApprovedChange(release, changeId, order, tenantId, actorId, now);
                order += 10;
            }
        }
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", release.getId(), "RELEASE_CREATED",
                null, release, "创建发布单并生成默认门禁清单");
        return release;
    }

    @Transactional
    public ReleasePlan update(Long id, ReleaseCommand command, Long tenantId, Long actorId) {
        validateCommand(command, tenantId, actorId);
        ReleasePlan release = lockRelease(id, tenantId);
        ensurePlanningEditable(release);
        ReleasePlan before = copy(release);
        applyCommand(release, command, tenantId, actorId);
        invalidateGoDecision(release);
        release.setUpdatedAt(LocalDateTime.now());
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", id, "RELEASE_UPDATED",
                before, release, "更新发布范围或计划窗口，需重新完成 Go/No-Go 决策");
        return release;
    }

    @Transactional
    public ReleaseChangeView linkChange(Long releaseId, Long changeId, Integer sortOrder, Long tenantId, Long actorId) {
        ReleasePlan release = lockRelease(releaseId, tenantId);
        ensurePlanningEditable(release);
        ReleaseChangeLink existing = changeLinkMapper.selectOne(new LambdaQueryWrapper<ReleaseChangeLink>()
                .eq(ReleaseChangeLink::getTenantId, tenantId)
                .eq(ReleaseChangeLink::getReleaseId, releaseId)
                .eq(ReleaseChangeLink::getChangeId, changeId));
        if (existing != null) return new ReleaseChangeView(existing, findChange(changeId, tenantId));
        ReleaseChangeLink link = linkApprovedChange(release, changeId, normalizeOrder(sortOrder), tenantId, actorId,
                LocalDateTime.now());
        invalidateGoDecision(release);
        release.setUpdatedAt(LocalDateTime.now());
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", releaseId, "RELEASE_CHANGE_LINKED",
                null, link, "将已批准变更纳入发布范围");
        return new ReleaseChangeView(link, findChange(changeId, tenantId));
    }

    @Transactional
    public void unlinkChange(Long releaseId, Long changeId, Long tenantId, Long actorId) {
        ReleasePlan release = lockRelease(releaseId, tenantId);
        ensurePlanningEditable(release);
        ReleaseChangeLink link = changeLinkMapper.selectOne(new LambdaQueryWrapper<ReleaseChangeLink>()
                .eq(ReleaseChangeLink::getTenantId, tenantId)
                .eq(ReleaseChangeLink::getReleaseId, releaseId)
                .eq(ReleaseChangeLink::getChangeId, changeId));
        if (link == null) throw new BusinessException("该发布单未关联此变更");
        changeLinkMapper.deleteById(link.getId());
        invalidateGoDecision(release);
        release.setUpdatedAt(LocalDateTime.now());
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", releaseId, "RELEASE_CHANGE_UNLINKED",
                link, null, "移除发布范围中的变更");
    }

    @Transactional
    public ReleaseChecklistItem addChecklist(Long releaseId, ChecklistCommand command, Long tenantId, Long actorId) {
        ReleasePlan release = lockRelease(releaseId, tenantId);
        ensurePlanningEditable(release);
        validateChecklistCommand(command);
        LocalDateTime now = LocalDateTime.now();
        ReleaseChecklistItem item = new ReleaseChecklistItem();
        item.setTenantId(tenantId);
        item.setReleaseId(releaseId);
        item.setPhase(command.phase());
        item.setTitle(normalize(command.title()));
        item.setRequiredItem(command.requiredItem() == null || command.requiredItem());
        item.setStatus(ReleaseChecklistStatus.PENDING);
        item.setSortOrder(normalizeOrder(command.sortOrder()));
        item.setCreatedBy(actorId);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        checklistMapper.insert(item);
        invalidateGoDecision(release);
        release.setUpdatedAt(now);
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", releaseId, "RELEASE_CHECKLIST_CREATED",
                null, item, "新增发布门禁项");
        return item;
    }

    @Transactional
    public ReleaseChecklistItem updateChecklist(Long itemId, ChecklistUpdateCommand command, Long tenantId, Long actorId) {
        if (command.status() == null) throw new BusinessException("请指定清单结果");
        ReleaseChecklistItem item = checklistMapper.selectOne(new LambdaQueryWrapper<ReleaseChecklistItem>()
                .eq(ReleaseChecklistItem::getId, itemId)
                .eq(ReleaseChecklistItem::getTenantId, tenantId));
        if (item == null) throw new BusinessException("发布清单项不存在");
        ReleasePlan release = lockRelease(item.getReleaseId(), tenantId);
        ensureChecklistEditable(release);
        if (Boolean.TRUE.equals(item.getRequiredItem()) && command.status() == ReleaseChecklistStatus.SKIPPED) {
            throw new BusinessException("必做门禁项不能跳过，请通过、失败或将其改为非必做项");
        }
        ReleaseChecklistItem before = copy(item);
        LocalDateTime now = LocalDateTime.now();
        item.setStatus(command.status());
        item.setNote(optional(command.note(), 1000, "清单说明"));
        if (command.status() == ReleaseChecklistStatus.PENDING) {
            item.setVerifiedBy(null);
            item.setVerifiedAt(null);
        } else {
            item.setVerifiedBy(actorId);
            item.setVerifiedAt(now);
        }
        item.setUpdatedAt(now);
        checklistMapper.updateById(item);
        if (release.getStatus() == ReleaseStatus.READY && item.getPhase() == ReleaseChecklistPhase.PRECHECK) {
            invalidateGoDecision(release);
            release.setUpdatedAt(now);
            releaseMapper.updateById(release);
        }
        auditLogService.record(tenantId, actorId, "RELEASE_CHECKLIST", itemId, "RELEASE_CHECKLIST_UPDATED",
                before, item, "记录发布门禁执行证据");
        return item;
    }

    @Transactional
    public ReleasePlan decideGoNoGo(Long id, GoNoGoCommand command, Long tenantId, Long actorId) {
        if (command.go() == null) throw new BusinessException("请明确选择 Go 或 No-Go");
        ReleasePlan release = lockRelease(id, tenantId);
        ensurePlanningEditable(release);
        String comment = optional(command.comment(), 500, "决策说明");
        GoReadiness readiness = GoReadiness.none();
        if (Boolean.TRUE.equals(command.go())) {
            readiness = validateReadyForGo(release, tenantId);
            release.setGoNoGoStatus(ReleaseGateDecision.GO);
            release.setStatus(ReleaseStatus.READY);
        } else {
            if (comment == null) throw new BusinessException("选择 No-Go 时必须填写阻断原因");
            release.setGoNoGoStatus(ReleaseGateDecision.NO_GO);
            release.setStatus(ReleaseStatus.DRAFT);
        }
        LocalDateTime now = LocalDateTime.now();
        release.setDecisionComment(comment);
        release.setDecidedBy(actorId);
        release.setDecidedAt(now);
        release.setUpdatedAt(now);
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", id,
                Boolean.TRUE.equals(command.go()) ? "RELEASE_GO_APPROVED" : "RELEASE_NO_GO",
                null, release, Boolean.TRUE.equals(command.go()) ? "Go/No-Go 门禁通过" : "Go/No-Go 门禁阻断");
        if (Boolean.TRUE.equals(command.go()) && !readiness.freezeOverrides().isEmpty()) {
            auditLogService.record(tenantId, actorId, "RELEASE_PLAN", id, "RELEASE_FREEZE_OVERRIDE",
                    null, readiness.freezeOverrides(), "紧急变更在冻结窗口内获准发布，已保留例外审计证据");
        }
        return release;
    }

    @Transactional
    public ReleasePlan start(Long id, Long tenantId, Long actorId) {
        ReleasePlan release = lockRelease(id, tenantId);
        if (release.getStatus() != ReleaseStatus.READY || release.getGoNoGoStatus() != ReleaseGateDecision.GO) {
            throw new BusinessException("只有已通过 Go 决策的发布单可以开始实施");
        }
        if (!requiredPhasePassed(id, tenantId, ReleaseChecklistPhase.PRECHECK)) {
            throw new BusinessException("预检门禁尚未全部通过，不能开始发布");
        }
        LocalDateTime now = LocalDateTime.now();
        release.setStatus(ReleaseStatus.IN_PROGRESS);
        release.setStartedAt(now);
        release.setUpdatedAt(now);
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", id, "RELEASE_STARTED",
                ReleaseStatus.READY, ReleaseStatus.IN_PROGRESS, "开始发布实施窗口");
        return release;
    }

    @Transactional
    public ReleasePlan verify(Long id, String note, Long tenantId, Long actorId) {
        ReleasePlan release = lockRelease(id, tenantId);
        if (release.getStatus() != ReleaseStatus.IN_PROGRESS) throw new BusinessException("当前发布不在实施中状态");
        if (!allRequiredPassed(id, tenantId)) throw new BusinessException("仍有必做部署或验证门禁未通过，不能完成发布");
        LocalDateTime now = LocalDateTime.now();
        release.setStatus(ReleaseStatus.VERIFIED);
        release.setOutcomeNote(optional(note, 1000, "验证结论"));
        release.setCompletedAt(now);
        release.setUpdatedAt(now);
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", id, "RELEASE_VERIFIED",
                ReleaseStatus.IN_PROGRESS, ReleaseStatus.VERIFIED, "发布完成并通过上线验证");
        return release;
    }

    @Transactional
    public ReleasePlan rollback(Long id, String reason, Long tenantId, Long actorId) {
        String normalizedReason = optional(reason, 1000, "回滚说明");
        if (normalizedReason == null) throw new BusinessException("回滚必须填写原因和处置结论");
        ReleasePlan release = lockRelease(id, tenantId);
        if (release.getStatus() != ReleaseStatus.READY && release.getStatus() != ReleaseStatus.IN_PROGRESS) {
            throw new BusinessException("当前状态不允许执行回滚");
        }
        LocalDateTime now = LocalDateTime.now();
        ReleaseStatus before = release.getStatus();
        release.setStatus(ReleaseStatus.ROLLED_BACK);
        release.setOutcomeNote(normalizedReason);
        release.setCompletedAt(now);
        release.setUpdatedAt(now);
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", id, "RELEASE_ROLLED_BACK",
                before, ReleaseStatus.ROLLED_BACK, "发布回滚：" + normalizedReason);
        return release;
    }

    @Transactional
    public ReleasePlan cancel(Long id, String reason, Long tenantId, Long actorId) {
        String normalizedReason = optional(reason, 1000, "取消说明");
        if (normalizedReason == null) throw new BusinessException("取消发布必须填写原因");
        ReleasePlan release = lockRelease(id, tenantId);
        if (release.getStatus() != ReleaseStatus.DRAFT && release.getStatus() != ReleaseStatus.READY) {
            throw new BusinessException("只有草稿或就绪状态的发布可以取消");
        }
        LocalDateTime now = LocalDateTime.now();
        ReleaseStatus before = release.getStatus();
        release.setStatus(ReleaseStatus.CANCELLED);
        release.setOutcomeNote(normalizedReason);
        release.setCompletedAt(now);
        release.setUpdatedAt(now);
        releaseMapper.updateById(release);
        auditLogService.record(tenantId, actorId, "RELEASE_PLAN", id, "RELEASE_CANCELLED",
                before, ReleaseStatus.CANCELLED, "取消发布：" + normalizedReason);
        return release;
    }

    private void validateCommand(ReleaseCommand command, Long tenantId, Long actorId) {
        if (normalize(command.name()) == null) throw new BusinessException("发布名称不能为空");
        if (command.name().trim().length() > 120) throw new BusinessException("发布名称不能超过 120 个字符");
        optional(command.description(), 4000, "发布说明");
        if (command.environment() == null) throw new BusinessException("请选择发布环境");
        if (command.plannedStart() != null && command.plannedEnd() != null
                && !command.plannedEnd().isAfter(command.plannedStart())) {
            throw new BusinessException("计划结束时间必须晚于开始时间");
        }
        if (command.changeIds() != null && command.changeIds().size() > 40) {
            throw new BusinessException("单个发布单最多关联 40 项变更");
        }
        Long managerId = command.releaseManagerId() == null ? actorId : command.releaseManagerId();
        if (!authService.isStaffMember(managerId, tenantId)) throw new BusinessException("发布负责人必须是当前租户的客服或管理员");
    }

    private void applyCommand(ReleasePlan release, ReleaseCommand command, Long tenantId, Long actorId) {
        release.setName(normalize(command.name()));
        release.setDescription(optional(command.description(), 4000, "发布说明"));
        release.setEnvironment(command.environment());
        release.setReleaseManagerId(command.releaseManagerId() == null ? actorId : command.releaseManagerId());
        release.setPlannedStart(command.plannedStart());
        release.setPlannedEnd(command.plannedEnd());
    }

    private GoReadiness validateReadyForGo(ReleasePlan release, Long tenantId) {
        if (release.getPlannedStart() == null || release.getPlannedEnd() == null) {
            throw new BusinessException("Go 决策前必须设置完整的发布窗口");
        }
        List<ReleaseChangeLink> links = changeLinks(release.getId(), tenantId);
        if (links.isEmpty()) throw new BusinessException("Go 决策前至少要关联一项已批准变更");
        boolean emergencyOnly = true;
        for (ReleaseChangeLink link : links) {
            ChangeRequest change = findChange(link.getChangeId(), tenantId);
            if (change == null || change.getStatus() != ChangeStatus.APPROVED) {
                throw new BusinessException("关联变更必须保持已批准状态，当前有不满足条件的变更");
            }
            if (change.getChangeType() != ChangeType.EMERGENCY) emergencyOnly = false;
        }
        if (!requiredPhasePassed(release.getId(), tenantId, ReleaseChecklistPhase.PRECHECK)) {
            throw new BusinessException("预检门禁尚未全部通过，不能给出 Go 决策");
        }
        List<ReleaseFreezeWindow> freezes = freezeOverlaps(release, tenantId);
        if (!freezes.isEmpty() && !emergencyOnly) {
            throw new BusinessException("发布窗口与启用的冻结策略冲突；只有全部为紧急变更的发布可以作为例外继续评估");
        }
        List<ReleaseConflictView> blockingConflicts = conflictsFor(release, tenantId).stream()
                .filter(ReleaseConflictView::blocking).toList();
        if (!blockingConflicts.isEmpty()) {
            throw new BusinessException("发布窗口与已就绪或实施中的发布存在受影响配置项冲突，请调整窗口或发布范围");
        }
        return new GoReadiness(freezes);
    }

    private ReleaseChangeLink linkApprovedChange(ReleasePlan release, Long changeId, int sortOrder, Long tenantId,
                                                  Long actorId, LocalDateTime now) {
        if (changeId == null) throw new BusinessException("变更 ID 不能为空");
        ChangeRequest change = findChange(changeId, tenantId);
        if (change == null) throw new BusinessException("关联的变更不存在");
        if (change.getStatus() != ChangeStatus.APPROVED) throw new BusinessException("只能关联已批准的变更");
        ReleaseChangeLink link = new ReleaseChangeLink();
        link.setTenantId(tenantId);
        link.setReleaseId(release.getId());
        link.setChangeId(changeId);
        link.setSortOrder(sortOrder);
        link.setCreatedBy(actorId);
        link.setCreatedAt(now);
        changeLinkMapper.insert(link);
        return link;
    }

    private void seedChecklist(Long releaseId, Long tenantId, Long actorId, LocalDateTime now) {
        for (ChecklistSeed seed : DEFAULT_CHECKLIST) {
            ReleaseChecklistItem item = new ReleaseChecklistItem();
            item.setTenantId(tenantId);
            item.setReleaseId(releaseId);
            item.setPhase(seed.phase());
            item.setTitle(seed.title());
            item.setRequiredItem(seed.requiredItem());
            item.setStatus(ReleaseChecklistStatus.PENDING);
            item.setSortOrder(seed.sortOrder());
            item.setCreatedBy(actorId);
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            checklistMapper.insert(item);
        }
    }

    private void validateChecklistCommand(ChecklistCommand command) {
        if (command.phase() == null) throw new BusinessException("请选择清单阶段");
        String title = normalize(command.title());
        if (title == null || title.length() > 180) throw new BusinessException("清单名称不能为空且不能超过 180 个字符");
    }

    private boolean requiredPhasePassed(Long releaseId, Long tenantId, ReleaseChecklistPhase phase) {
        List<ReleaseChecklistItem> items = checklist(releaseId, tenantId).stream()
                .filter(item -> item.getPhase() == phase && Boolean.TRUE.equals(item.getRequiredItem())).toList();
        return !items.isEmpty() && items.stream().allMatch(item -> item.getStatus() == ReleaseChecklistStatus.PASSED);
    }

    private boolean allRequiredPassed(Long releaseId, Long tenantId) {
        List<ReleaseChecklistItem> items = checklist(releaseId, tenantId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getRequiredItem())).toList();
        return !items.isEmpty() && items.stream().allMatch(item -> item.getStatus() == ReleaseChecklistStatus.PASSED);
    }

    private GateSummary gateSummary(Long releaseId, Long tenantId) {
        List<ReleaseChecklistItem> items = checklist(releaseId, tenantId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getRequiredItem())).toList();
        int passed = (int) items.stream().filter(item -> item.getStatus() == ReleaseChecklistStatus.PASSED).count();
        int failed = (int) items.stream().filter(item -> item.getStatus() == ReleaseChecklistStatus.FAILED).count();
        return new GateSummary(items.size(), passed, failed, items.size() - passed - failed);
    }

    private List<ReleaseFreezeWindow> freezeOverlaps(ReleasePlan release, Long tenantId) {
        if (release.getPlannedStart() == null || release.getPlannedEnd() == null) return List.of();
        return freezeWindows(tenantId).stream()
                .filter(freeze -> Boolean.TRUE.equals(freeze.getEnabled()))
                .filter(freeze -> freeze.getEnvironment() == null || freeze.getEnvironment() == release.getEnvironment())
                .filter(freeze -> windowsOverlap(release.getPlannedStart(), release.getPlannedEnd(),
                        freeze.getStartsAt(), freeze.getEndsAt()))
                .toList();
    }

    private List<ReleaseConflictView> conflictsFor(ReleasePlan release, Long tenantId) {
        if (release.getId() == null || release.getPlannedStart() == null || release.getPlannedEnd() == null) return List.of();
        Set<Long> affectedAssets = affectedAssets(release.getId(), tenantId);
        if (affectedAssets.isEmpty()) return List.of();
        List<ReleasePlan> candidates = releaseMapper.selectList(new LambdaQueryWrapper<ReleasePlan>()
                .eq(ReleasePlan::getTenantId, tenantId)
                .in(ReleasePlan::getStatus, List.of(ReleaseStatus.DRAFT, ReleaseStatus.READY, ReleaseStatus.IN_PROGRESS))
                .isNotNull(ReleasePlan::getPlannedStart)
                .isNotNull(ReleasePlan::getPlannedEnd)
                .last("LIMIT 300"));
        return candidates.stream()
                .filter(candidate -> !release.getId().equals(candidate.getId()))
                .filter(candidate -> candidate.getEnvironment() == release.getEnvironment())
                .filter(candidate -> windowsOverlap(release.getPlannedStart(), release.getPlannedEnd(),
                        candidate.getPlannedStart(), candidate.getPlannedEnd()))
                .map(candidate -> {
                    Set<Long> shared = new HashSet<>(affectedAssets);
                    shared.retainAll(affectedAssets(candidate.getId(), tenantId));
                    return new ReleaseConflictView(candidate, shared.stream().sorted().toList(),
                            candidate.getStatus() == ReleaseStatus.READY || candidate.getStatus() == ReleaseStatus.IN_PROGRESS);
                })
                .filter(conflict -> !conflict.sharedAssetIds().isEmpty())
                .toList();
    }

    private Set<Long> affectedAssets(Long releaseId, Long tenantId) {
        Set<Long> changeIds = changeLinks(releaseId, tenantId).stream()
                .map(ReleaseChangeLink::getChangeId).collect(java.util.stream.Collectors.toSet());
        if (changeIds.isEmpty()) return Set.of();
        return changeAssetLinkMapper.selectList(new LambdaQueryWrapper<ChangeAssetLink>()
                        .eq(ChangeAssetLink::getTenantId, tenantId)
                        .in(ChangeAssetLink::getChangeId, changeIds))
                .stream().map(ChangeAssetLink::getAssetId).collect(java.util.stream.Collectors.toSet());
    }

    private boolean windowsOverlap(LocalDateTime firstStart, LocalDateTime firstEnd,
                                   LocalDateTime secondStart, LocalDateTime secondEnd) {
        return firstStart != null && firstEnd != null && secondStart != null && secondEnd != null
                && firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private void validateFreeze(FreezeWindowCommand command) {
        String name = normalize(command.name());
        String reason = normalize(command.reason());
        if (name == null || name.length() > 120) throw new BusinessException("冻结窗口名称不能为空且不能超过 120 个字符");
        if (reason == null || reason.length() > 1000) throw new BusinessException("冻结原因不能为空且不能超过 1000 个字符");
        if (command.startsAt() == null || command.endsAt() == null || !command.endsAt().isAfter(command.startsAt())) {
            throw new BusinessException("冻结结束时间必须晚于开始时间");
        }
        if (command.endsAt().isAfter(command.startsAt().plusDays(120))) {
            throw new BusinessException("单个冻结窗口最多持续 120 天");
        }
    }

    private void applyFreeze(ReleaseFreezeWindow freeze, FreezeWindowCommand command) {
        freeze.setName(normalize(command.name()));
        freeze.setEnvironment(command.environment());
        freeze.setStartsAt(command.startsAt());
        freeze.setEndsAt(command.endsAt());
        freeze.setReason(normalize(command.reason()));
        freeze.setEnabled(command.enabled() == null || command.enabled());
    }

    private ReleaseFreezeWindow getFreeze(Long id, Long tenantId) {
        ReleaseFreezeWindow freeze = freezeMapper.selectOne(new LambdaQueryWrapper<ReleaseFreezeWindow>()
                .eq(ReleaseFreezeWindow::getId, id)
                .eq(ReleaseFreezeWindow::getTenantId, tenantId));
        if (freeze == null) throw new BusinessException("发布冻结窗口不存在");
        return freeze;
    }

    private List<ReleaseChecklistItem> checklist(Long releaseId, Long tenantId) {
        return checklistMapper.selectList(new LambdaQueryWrapper<ReleaseChecklistItem>()
                .eq(ReleaseChecklistItem::getTenantId, tenantId)
                .eq(ReleaseChecklistItem::getReleaseId, releaseId)
                .orderByAsc(ReleaseChecklistItem::getPhase)
                .orderByAsc(ReleaseChecklistItem::getSortOrder)
                .orderByAsc(ReleaseChecklistItem::getId));
    }

    private List<ReleaseChangeLink> changeLinks(Long releaseId, Long tenantId) {
        return changeLinkMapper.selectList(new LambdaQueryWrapper<ReleaseChangeLink>()
                .eq(ReleaseChangeLink::getTenantId, tenantId)
                .eq(ReleaseChangeLink::getReleaseId, releaseId)
                .orderByAsc(ReleaseChangeLink::getSortOrder)
                .orderByAsc(ReleaseChangeLink::getId));
    }

    private int linkedChangeCount(Long releaseId, Long tenantId) {
        Long count = changeLinkMapper.selectCount(new LambdaQueryWrapper<ReleaseChangeLink>()
                .eq(ReleaseChangeLink::getTenantId, tenantId)
                .eq(ReleaseChangeLink::getReleaseId, releaseId));
        return count == null ? 0 : count.intValue();
    }

    private ChangeRequest findChange(Long changeId, Long tenantId) {
        return changeMapper.selectOne(new LambdaQueryWrapper<ChangeRequest>()
                .eq(ChangeRequest::getId, changeId)
                .eq(ChangeRequest::getTenantId, tenantId));
    }

    private ReleasePlan getRelease(Long id, Long tenantId) {
        ReleasePlan release = releaseMapper.selectOne(new LambdaQueryWrapper<ReleasePlan>()
                .eq(ReleasePlan::getId, id).eq(ReleasePlan::getTenantId, tenantId));
        if (release == null) throw new BusinessException("发布单不存在");
        return release;
    }

    private ReleasePlan lockRelease(Long id, Long tenantId) {
        ReleasePlan release = releaseMapper.selectForUpdate(id, tenantId);
        if (release == null) throw new BusinessException("发布单不存在");
        return release;
    }

    private void ensurePlanningEditable(ReleasePlan release) {
        if (release.getStatus() != ReleaseStatus.DRAFT && release.getStatus() != ReleaseStatus.READY) {
            throw new BusinessException("发布已开始或已结束，不能修改发布范围和门禁定义");
        }
    }

    private void ensureChecklistEditable(ReleasePlan release) {
        if (release.getStatus() == ReleaseStatus.VERIFIED || release.getStatus() == ReleaseStatus.ROLLED_BACK
                || release.getStatus() == ReleaseStatus.CANCELLED) {
            throw new BusinessException("已结束的发布不允许再更新执行清单");
        }
    }

    private void invalidateGoDecision(ReleasePlan release) {
        if (release.getStatus() == ReleaseStatus.READY || release.getGoNoGoStatus() != ReleaseGateDecision.PENDING) {
            release.setStatus(ReleaseStatus.DRAFT);
            release.setGoNoGoStatus(ReleaseGateDecision.PENDING);
            release.setDecisionComment(null);
            release.setDecidedBy(null);
            release.setDecidedAt(null);
        }
    }

    private int normalizeOrder(Integer order) {
        return order == null ? 100 : Math.max(0, Math.min(order, 9999));
    }

    private String optional(String raw, int maxLength, String label) {
        String value = normalize(raw);
        if (value != null && value.length() > maxLength) throw new BusinessException(label + "不能超过 " + maxLength + " 个字符");
        return value;
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        return value.isBlank() ? null : value;
    }

    private ReleasePlan copy(ReleasePlan source) {
        ReleasePlan copy = new ReleasePlan();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setReleaseNo(source.getReleaseNo());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setEnvironment(source.getEnvironment());
        copy.setReleaseManagerId(source.getReleaseManagerId());
        copy.setPlannedStart(source.getPlannedStart());
        copy.setPlannedEnd(source.getPlannedEnd());
        copy.setStatus(source.getStatus());
        copy.setGoNoGoStatus(source.getGoNoGoStatus());
        copy.setDecisionComment(source.getDecisionComment());
        copy.setDecidedBy(source.getDecidedBy());
        copy.setDecidedAt(source.getDecidedAt());
        copy.setOutcomeNote(source.getOutcomeNote());
        copy.setStartedAt(source.getStartedAt());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private ReleaseChecklistItem copy(ReleaseChecklistItem source) {
        ReleaseChecklistItem copy = new ReleaseChecklistItem();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setReleaseId(source.getReleaseId());
        copy.setPhase(source.getPhase());
        copy.setTitle(source.getTitle());
        copy.setRequiredItem(source.getRequiredItem());
        copy.setStatus(source.getStatus());
        copy.setNote(source.getNote());
        copy.setSortOrder(source.getSortOrder());
        copy.setVerifiedBy(source.getVerifiedBy());
        copy.setVerifiedAt(source.getVerifiedAt());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private ReleaseFreezeWindow copyFreeze(ReleaseFreezeWindow source) {
        ReleaseFreezeWindow copy = new ReleaseFreezeWindow();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setName(source.getName());
        copy.setEnvironment(source.getEnvironment());
        copy.setStartsAt(source.getStartsAt());
        copy.setEndsAt(source.getEndsAt());
        copy.setReason(source.getReason());
        copy.setEnabled(source.getEnabled());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    public record ReleaseCommand(String name, String description, ReleaseEnvironment environment,
                                 Long releaseManagerId, LocalDateTime plannedStart, LocalDateTime plannedEnd,
                                 List<Long> changeIds) { }

    public record ChecklistCommand(ReleaseChecklistPhase phase, String title, Boolean requiredItem, Integer sortOrder) { }

    public record ChecklistUpdateCommand(ReleaseChecklistStatus status, String note) { }

    public record GoNoGoCommand(Boolean go, String comment) { }

    public record FreezeWindowCommand(String name, ReleaseEnvironment environment, LocalDateTime startsAt,
                                      LocalDateTime endsAt, String reason, Boolean enabled) { }

    public record ReleaseCenterView(List<ReleaseView> releases, List<ChangeRequest> availableChanges,
                                    List<ReleaseFreezeWindow> freezeWindows) { }

    public record ReleaseView(ReleasePlan release, GateSummary gate, Integer linkedChangeCount,
                              Integer conflictCount, Integer freezeConflictCount) { }

    public record ReleaseDetail(ReleasePlan release, GateSummary gate, List<ReleaseChangeView> changes,
                                List<ReleaseChecklistItem> checklist, List<ReleaseConflictView> conflicts,
                                List<ReleaseFreezeWindow> freezeOverlaps) { }

    public record ReleaseChangeView(ReleaseChangeLink link, ChangeRequest change) { }

    public record ReleaseConflictView(ReleasePlan conflictingRelease, List<Long> sharedAssetIds, boolean blocking) { }

    public record ReleaseCalendarView(LocalDateTime startsAt, LocalDateTime endsAt, List<ReleasePlan> releases,
                                      List<ReleaseFreezeWindow> freezeWindows) { }

    public record GateSummary(int requiredTotal, int passedRequired, int failedRequired, int pendingRequired) { }

    private record GoReadiness(List<ReleaseFreezeWindow> freezeOverrides) {
        private static GoReadiness none() { return new GoReadiness(List.of()); }
    }

    private record ChecklistSeed(ReleaseChecklistPhase phase, String title, boolean requiredItem, int sortOrder) { }
}
