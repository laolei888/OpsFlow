package com.opsflow.change;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.cmdb.CmdbAsset;
import com.opsflow.cmdb.CmdbAssetMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.messaging.AsyncNotificationPublisher;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChangeService {
    private static final Set<ChangeStatus> TERMINAL_STATUSES = Set.of(
            ChangeStatus.COMPLETED, ChangeStatus.FAILED, ChangeStatus.CANCELLED, ChangeStatus.REJECTED);
    private static final Set<String> LINK_TYPES = Set.of("AFFECTED", "HOSTED_ON", "RELATED");

    private final ChangeRequestMapper changeMapper;
    private final ChangeTaskMapper taskMapper;
    private final ChangeAssetLinkMapper assetLinkMapper;
    private final CmdbAssetMapper assetMapper;
    private final TicketService ticketService;
    private final AuthService authService;
    private final AsyncNotificationPublisher notificationPublisher;
    private final AuditLogService auditLogService;

    public List<ChangeRequest> list(Long tenantId, Long requesterId, ChangeStatus status,
                                    ChangeRisk risk, ChangeType type, String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<ChangeRequest> query = new LambdaQueryWrapper<ChangeRequest>()
                .eq(ChangeRequest::getTenantId, tenantId)
                .orderByDesc(ChangeRequest::getCreatedAt)
                .orderByDesc(ChangeRequest::getId)
                .last("LIMIT " + safeLimit);
        if (requesterId != null) query.eq(ChangeRequest::getRequesterId, requesterId);
        if (status != null) query.eq(ChangeRequest::getStatus, status);
        if (risk != null) query.eq(ChangeRequest::getRiskLevel, risk);
        if (type != null) query.eq(ChangeRequest::getChangeType, type);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(ChangeRequest::getChangeNo, search)
                    .or().like(ChangeRequest::getTitle, search)
                    .or().like(ChangeRequest::getDescription, search));
        }
        return changeMapper.selectList(query);
    }

    public ChangeDetail detail(Long id, Long tenantId, Long requesterId) {
        ChangeRequest change = findVisible(id, tenantId, requesterId);
        List<ChangeTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ChangeTask>()
                .eq(ChangeTask::getTenantId, tenantId)
                .eq(ChangeTask::getChangeId, id)
                .orderByAsc(ChangeTask::getSortOrder)
                .orderByAsc(ChangeTask::getId));
        List<ChangeAssetLink> links = assetLinkMapper.selectList(new LambdaQueryWrapper<ChangeAssetLink>()
                .eq(ChangeAssetLink::getTenantId, tenantId)
                .eq(ChangeAssetLink::getChangeId, id)
                .orderByDesc(ChangeAssetLink::getCreatedAt));
        if (links.isEmpty()) return new ChangeDetail(change, tasks, List.of());
        Set<Long> assetIds = new HashSet<>();
        links.forEach(link -> assetIds.add(link.getAssetId()));
        Map<Long, CmdbAsset> assets = new HashMap<>();
        assetMapper.selectBatchIds(assetIds).forEach(asset -> {
            if (tenantId.equals(asset.getTenantId())) assets.put(asset.getId(), asset);
        });
        List<ChangeAssetView> assetViews = links.stream()
                .filter(link -> assets.containsKey(link.getAssetId()))
                .map(link -> new ChangeAssetView(link.getAssetId(), link.getLinkType(), link.getNote(),
                        assets.get(link.getAssetId()), link.getCreatedAt()))
                .toList();
        return new ChangeDetail(change, tasks, assetViews);
    }

    @Transactional
    public ChangeRequest create(ChangeCommand command, String rawIdempotencyKey,
                                Long requesterScope, Long requesterId, Long tenantId) {
        validateCommand(command);
        String idempotencyKey = normalizeKey(rawIdempotencyKey);
        String requestHash = requestHash(command);
        if (idempotencyKey != null) {
            ChangeRequest existing = changeMapper.selectOne(new LambdaQueryWrapper<ChangeRequest>()
                    .eq(ChangeRequest::getTenantId, tenantId)
                    .eq(ChangeRequest::getRequesterId, requesterId)
                    .eq(ChangeRequest::getRequestKey, idempotencyKey));
            if (existing != null) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new BusinessException("幂等键已用于其他变更申请");
                }
                return existing;
            }
        }
        if (command.linkedTicketId() != null) {
            ticketService.getVisible(command.linkedTicketId(), tenantId, requesterScope);
        }

        LocalDateTime now = LocalDateTime.now();
        ChangeRequest change = new ChangeRequest();
        change.setTenantId(tenantId);
        change.setChangeNo("CHG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        change.setRequesterId(requesterId);
        change.setTitle(normalize(command.title()));
        change.setDescription(normalize(command.description()));
        change.setChangeType(command.changeType());
        change.setRiskLevel(command.riskLevel());
        change.setImpactScope(normalize(command.impactScope()));
        change.setImplementationPlan(normalize(command.implementationPlan()));
        change.setValidationPlan(normalize(command.validationPlan()));
        change.setRollbackPlan(normalize(command.rollbackPlan()));
        change.setScheduledStart(command.scheduledStart());
        change.setScheduledEnd(command.scheduledEnd());
        change.setStatus(isPreApproved(command) ? ChangeStatus.APPROVED : ChangeStatus.PENDING_APPROVAL);
        change.setLinkedTicketId(command.linkedTicketId());
        change.setRequestKey(idempotencyKey);
        change.setRequestHash(idempotencyKey == null ? null : requestHash);
        change.setCreatedAt(now);
        change.setUpdatedAt(now);
        try {
            changeMapper.insert(change);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey != null) {
                ChangeRequest existing = changeMapper.selectOne(new LambdaQueryWrapper<ChangeRequest>()
                        .eq(ChangeRequest::getTenantId, tenantId)
                        .eq(ChangeRequest::getRequesterId, requesterId)
                        .eq(ChangeRequest::getRequestKey, idempotencyKey));
                if (existing != null && requestHash.equals(existing.getRequestHash())) return existing;
            }
            throw new BusinessException("变更申请提交失败，请稍后重试");
        }
        createTasks(change, command.taskTitles(), tenantId, requesterId, now);
        String action = change.getStatus() == ChangeStatus.APPROVED ? "CHANGE_AUTO_APPROVED" : "CHANGE_CREATED";
        auditLogService.record(tenantId, requesterId, "CHANGE_REQUEST", change.getId(), action,
                null, change, change.getStatus() == ChangeStatus.APPROVED ? "标准低风险变更自动预批准" : "提交变更申请");
        if (change.getStatus() == ChangeStatus.PENDING_APPROVAL) {
            notifyApprovers(change);
        } else {
            notifyRequester(change, "CHANGE_APPROVED", "变更申请已预批准", "标准低风险变更可进入实施阶段。",
                    "CHANGE_APPROVED:" + change.getId());
        }
        return change;
    }

    @Transactional
    public ChangeRequest approve(Long id, String comment, Long actorId, Long tenantId) {
        ChangeRequest change = lock(id, tenantId);
        if (change.getStatus() != ChangeStatus.PENDING_APPROVAL) {
            throw new BusinessException("当前变更不在待审批状态");
        }
        change.setStatus(ChangeStatus.APPROVED);
        change.setDecisionComment(normalize(comment));
        change.setDecidedBy(actorId);
        change.setDecidedAt(LocalDateTime.now());
        change.setUpdatedAt(LocalDateTime.now());
        changeMapper.updateById(change);
        auditLogService.record(tenantId, actorId, "CHANGE_REQUEST", id, "CHANGE_APPROVED",
                null, change, "通过变更申请");
        notifyRequester(change, "CHANGE_APPROVED", "变更申请已通过", "审批人已批准「" + change.getTitle() + "」。",
                "CHANGE_APPROVED:" + id);
        return change;
    }

    @Transactional
    public ChangeRequest reject(Long id, String comment, Long actorId, Long tenantId) {
        String reason = normalize(comment);
        if (reason == null) throw new BusinessException("驳回变更必须填写原因");
        ChangeRequest change = lock(id, tenantId);
        if (change.getStatus() != ChangeStatus.PENDING_APPROVAL) {
            throw new BusinessException("当前变更不在待审批状态");
        }
        change.setStatus(ChangeStatus.REJECTED);
        change.setDecisionComment(reason);
        change.setDecidedBy(actorId);
        change.setDecidedAt(LocalDateTime.now());
        change.setUpdatedAt(LocalDateTime.now());
        changeMapper.updateById(change);
        auditLogService.record(tenantId, actorId, "CHANGE_REQUEST", id, "CHANGE_REJECTED",
                null, change, "驳回变更申请");
        notifyRequester(change, "CHANGE_REJECTED", "变更申请已驳回", "驳回原因：" + reason,
                "CHANGE_REJECTED:" + id);
        return change;
    }

    @Transactional
    public ChangeRequest transition(Long id, ChangeStatus target, Long actorId, Long tenantId) {
        if (target == null) throw new BusinessException("目标状态不能为空");
        ChangeRequest change = lock(id, tenantId);
        ChangeStatus before = change.getStatus();
        validateTransition(before, target);
        if (target == ChangeStatus.COMPLETED && taskMapper.selectCount(new LambdaQueryWrapper<ChangeTask>()
                .eq(ChangeTask::getTenantId, tenantId)
                .eq(ChangeTask::getChangeId, id)
                .ne(ChangeTask::getStatus, ChangeTaskStatus.DONE)) > 0) {
            throw new BusinessException("仍有未完成的实施任务，不能完成变更");
        }
        LocalDateTime now = LocalDateTime.now();
        change.setStatus(target);
        if (target == ChangeStatus.IN_PROGRESS && change.getStartedAt() == null) change.setStartedAt(now);
        if (target == ChangeStatus.COMPLETED || target == ChangeStatus.FAILED || target == ChangeStatus.CANCELLED) {
            change.setCompletedAt(now);
        }
        change.setUpdatedAt(now);
        if (changeMapper.updateById(change) == 0) throw new BusinessException("变更状态更新失败，请刷新后重试");
        auditLogService.record(tenantId, actorId, "CHANGE_REQUEST", id, "CHANGE_STATUS_CHANGED",
                Map.of("status", before.name()), Map.of("status", target.name()), "变更状态流转");
        notifyRequester(change, "CHANGE_STATUS_CHANGED", "变更状态已更新",
                "「" + change.getTitle() + "」当前状态：" + target.name(),
                "CHANGE_STATUS_CHANGED:" + id + ":" + target.name());
        return change;
    }

    @Transactional
    public ChangeTask createTask(Long changeId, TaskCommand command, Long tenantId, Long actorId) {
        ChangeRequest change = find(changeId, tenantId);
        ensureEditable(change);
        String title = normalize(command.title());
        if (title == null || title.length() > 160) throw new BusinessException("任务名称不能为空且不能超过 160 个字符");
        validateAssignee(command.assigneeId(), tenantId);
        LocalDateTime now = LocalDateTime.now();
        ChangeTask task = new ChangeTask();
        task.setTenantId(tenantId);
        task.setChangeId(changeId);
        task.setTitle(title);
        task.setAssigneeId(command.assigneeId());
        task.setSortOrder(command.sortOrder() == null ? 100 : Math.max(0, Math.min(command.sortOrder(), 9999)));
        task.setStatus(ChangeTaskStatus.TODO);
        task.setCreatedBy(actorId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        auditLogService.record(tenantId, actorId, "CHANGE_REQUEST", changeId, "CHANGE_TASK_CREATED",
                null, task, "新增变更实施任务");
        return task;
    }

    @Transactional
    public ChangeTask updateTask(Long taskId, TaskUpdateCommand command, Long tenantId, Long actorId) {
        ChangeTask task = taskMapper.selectOne(new LambdaQueryWrapper<ChangeTask>()
                .eq(ChangeTask::getId, taskId)
                .eq(ChangeTask::getTenantId, tenantId));
        if (task == null) throw new BusinessException("变更实施任务不存在");
        ChangeRequest change = find(task.getChangeId(), tenantId);
        ensureEditable(change);
        ChangeTask before = copy(task);
        if (command.title() != null) {
            String title = normalize(command.title());
            if (title == null || title.length() > 160) throw new BusinessException("任务名称不能为空且不能超过 160 个字符");
            task.setTitle(title);
        }
        if (command.assigneeId() != null) {
            validateAssignee(command.assigneeId(), tenantId);
            task.setAssigneeId(command.assigneeId());
        }
        if (command.status() != null) {
            task.setStatus(command.status());
            task.setCompletedAt(command.status() == ChangeTaskStatus.DONE ? LocalDateTime.now() : null);
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        auditLogService.record(tenantId, actorId, "CHANGE_REQUEST", change.getId(), "CHANGE_TASK_UPDATED",
                before, task, "更新变更实施任务");
        return task;
    }

    @Transactional
    public ChangeAssetView linkAsset(Long changeId, AssetLinkCommand command, Long tenantId, Long actorId) {
        ChangeRequest change = find(changeId, tenantId);
        ensureEditable(change);
        CmdbAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<CmdbAsset>()
                .eq(CmdbAsset::getId, command.assetId())
                .eq(CmdbAsset::getTenantId, tenantId));
        if (asset == null) throw new BusinessException("配置项不存在");
        if (asset.getStatus().name().equals("RETIRED")) throw new BusinessException("已退役资产不能关联变更");
        String linkType = normalizeLinkType(command.linkType());
        ChangeAssetLink existing = assetLinkMapper.selectOne(new LambdaQueryWrapper<ChangeAssetLink>()
                .eq(ChangeAssetLink::getTenantId, tenantId)
                .eq(ChangeAssetLink::getChangeId, changeId)
                .eq(ChangeAssetLink::getAssetId, asset.getId()));
        if (existing != null) return new ChangeAssetView(asset.getId(), existing.getLinkType(), existing.getNote(), asset, existing.getCreatedAt());
        ChangeAssetLink link = new ChangeAssetLink();
        link.setTenantId(tenantId);
        link.setChangeId(changeId);
        link.setAssetId(asset.getId());
        link.setLinkType(linkType);
        link.setNote(normalize(command.note()));
        link.setCreatedBy(actorId);
        link.setCreatedAt(LocalDateTime.now());
        assetLinkMapper.insert(link);
        ChangeAssetView view = new ChangeAssetView(asset.getId(), link.getLinkType(), link.getNote(), asset, link.getCreatedAt());
        auditLogService.record(tenantId, actorId, "CHANGE_REQUEST", changeId, "CHANGE_ASSET_LINKED",
                null, view, "关联变更影响配置项");
        return view;
    }

    @Transactional
    public void unlinkAsset(Long changeId, Long assetId, Long tenantId, Long actorId) {
        ChangeRequest change = find(changeId, tenantId);
        ensureEditable(change);
        ChangeAssetLink link = assetLinkMapper.selectOne(new LambdaQueryWrapper<ChangeAssetLink>()
                .eq(ChangeAssetLink::getTenantId, tenantId)
                .eq(ChangeAssetLink::getChangeId, changeId)
                .eq(ChangeAssetLink::getAssetId, assetId));
        if (link == null) throw new BusinessException("变更尚未关联该配置项");
        assetLinkMapper.delete(new LambdaQueryWrapper<ChangeAssetLink>()
                .eq(ChangeAssetLink::getTenantId, tenantId)
                .eq(ChangeAssetLink::getChangeId, changeId)
                .eq(ChangeAssetLink::getAssetId, assetId));
        auditLogService.record(tenantId, actorId, "CHANGE_REQUEST", changeId, "CHANGE_ASSET_UNLINKED",
                Map.of("assetId", assetId), null, "解除变更配置项关联");
    }

    private void validateCommand(ChangeCommand command) {
        String title = normalize(command.title());
        String description = normalize(command.description());
        String implementation = normalize(command.implementationPlan());
        String rollback = normalize(command.rollbackPlan());
        if (title == null || title.length() > 160) throw new BusinessException("变更标题不能为空且不能超过 160 个字符");
        if (description == null || description.length() > 10000) throw new BusinessException("变更描述不能为空且不能超过 10000 个字符");
        if (command.changeType() == null || command.riskLevel() == null) throw new BusinessException("请选择变更类型和风险等级");
        if (implementation == null || implementation.length() > 10000) throw new BusinessException("实施方案不能为空且不能超过 10000 个字符");
        if (rollback == null || rollback.length() > 10000) throw new BusinessException("回滚方案不能为空且不能超过 10000 个字符");
        if (command.impactScope() != null && command.impactScope().trim().length() > 500) throw new BusinessException("影响范围不能超过 500 个字符");
        if (command.validationPlan() != null && command.validationPlan().trim().length() > 10000) throw new BusinessException("验证方案不能超过 10000 个字符");
        if (command.scheduledStart() != null && command.scheduledEnd() != null
                && !command.scheduledEnd().isAfter(command.scheduledStart())) {
            throw new BusinessException("计划结束时间必须晚于开始时间");
        }
        if (command.scheduledStart() != null && command.scheduledStart().isBefore(LocalDateTime.now().minusMinutes(2))
                && command.changeType() != ChangeType.EMERGENCY) {
            throw new BusinessException("计划开始时间不能早于当前时间");
        }
        if (command.taskTitles() != null && command.taskTitles().size() > 30) throw new BusinessException("单个变更最多 30 个实施任务");
    }

    private boolean isPreApproved(ChangeCommand command) {
        return command.changeType() == ChangeType.STANDARD && command.riskLevel() == ChangeRisk.LOW;
    }

    private void createTasks(ChangeRequest change, List<String> titles, Long tenantId, Long actorId, LocalDateTime now) {
        if (titles == null) return;
        int order = 10;
        for (String rawTitle : titles) {
            String title = normalize(rawTitle);
            if (title == null) continue;
            if (title.length() > 160) throw new BusinessException("实施任务名称不能超过 160 个字符");
            ChangeTask task = new ChangeTask();
            task.setTenantId(tenantId);
            task.setChangeId(change.getId());
            task.setTitle(title);
            task.setSortOrder(order);
            task.setStatus(ChangeTaskStatus.TODO);
            task.setCreatedBy(actorId);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            taskMapper.insert(task);
            order += 10;
        }
    }

    private void validateTransition(ChangeStatus before, ChangeStatus target) {
        boolean allowed = (before == ChangeStatus.APPROVED && (target == ChangeStatus.IN_PROGRESS || target == ChangeStatus.CANCELLED))
                || (before == ChangeStatus.IN_PROGRESS && (target == ChangeStatus.COMPLETED || target == ChangeStatus.FAILED));
        if (!allowed) throw new BusinessException("不允许从「" + before + "」流转到「" + target + "」");
    }

    private void ensureEditable(ChangeRequest change) {
        if (TERMINAL_STATUSES.contains(change.getStatus())) {
            throw new BusinessException("当前变更状态不允许修改任务或资产关联");
        }
    }

    private void validateAssignee(Long assigneeId, Long tenantId) {
        if (assigneeId != null && !authService.isStaffMember(assigneeId, tenantId)) {
            throw new BusinessException("任务负责人不存在或不是当前租户的客服账号");
        }
    }

    private void notifyApprovers(ChangeRequest change) {
        List<StaffSummary> staff = authService.staffMembers(change.getTenantId());
        for (StaffSummary member : staff) {
            if (member.roles().contains("ADMIN")) {
                notificationPublisher.enqueue(change.getTenantId(), member.id(), null,
                        "CHANGE_APPROVAL_REQUIRED", "新的变更待审批",
                        "变更「" + change.getTitle() + "」等待风险审批。",
                        "CHANGE_APPROVAL_REQUIRED:" + change.getId(),
                        "CHANGE_REQUEST", change.getId());
            }
        }
    }

    private void notifyRequester(ChangeRequest change, String type, String title, String content, String dedupeKey) {
        notificationPublisher.enqueue(change.getTenantId(), change.getRequesterId(), null,
                type, title, content, dedupeKey, "CHANGE_REQUEST", change.getId());
    }

    private ChangeRequest lock(Long id, Long tenantId) {
        ChangeRequest change = changeMapper.selectForUpdate(id, tenantId);
        if (change == null) throw new BusinessException("变更申请不存在");
        return change;
    }

    private ChangeRequest find(Long id, Long tenantId) {
        ChangeRequest change = changeMapper.selectOne(new LambdaQueryWrapper<ChangeRequest>()
                .eq(ChangeRequest::getId, id).eq(ChangeRequest::getTenantId, tenantId));
        if (change == null) throw new BusinessException("变更申请不存在");
        return change;
    }

    private ChangeRequest findVisible(Long id, Long tenantId, Long requesterId) {
        ChangeRequest change = find(id, tenantId);
        if (requesterId != null && !requesterId.equals(change.getRequesterId())) throw new BusinessException("变更申请不存在");
        return change;
    }

    private ChangeTask copy(ChangeTask source) {
        ChangeTask copy = new ChangeTask();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setChangeId(source.getChangeId());
        copy.setTitle(source.getTitle());
        copy.setAssigneeId(source.getAssigneeId());
        copy.setSortOrder(source.getSortOrder());
        copy.setStatus(source.getStatus());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String normalizeLinkType(String raw) {
        String linkType = raw == null || raw.isBlank() ? "AFFECTED" : raw.trim().toUpperCase(Locale.ROOT);
        if (!LINK_TYPES.contains(linkType)) throw new BusinessException("无效的变更资产关联类型");
        return linkType;
    }

    private String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim();
        if (key.length() > 128) throw new BusinessException("Idempotency-Key 不能超过 128 个字符");
        return key;
    }

    private String requestHash(ChangeCommand command) {
        String source = String.join("\u0000", value(command.title()), value(command.description()),
                command.changeType() == null ? "" : command.changeType().name(),
                command.riskLevel() == null ? "" : command.riskLevel().name(),
                value(command.implementationPlan()), value(command.rollbackPlan()),
                command.scheduledStart() == null ? "" : command.scheduledStart().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String value(String value) { return value == null ? "" : value.trim(); }

    private String normalize(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    public record ChangeCommand(String title, String description, ChangeType changeType, ChangeRisk riskLevel,
                                String impactScope, String implementationPlan, String validationPlan,
                                String rollbackPlan, java.time.LocalDateTime scheduledStart,
                                java.time.LocalDateTime scheduledEnd, Long linkedTicketId,
                                List<String> taskTitles) { }

    public record TaskCommand(String title, Long assigneeId, Integer sortOrder) { }

    public record TaskUpdateCommand(String title, Long assigneeId, ChangeTaskStatus status) { }

    public record AssetLinkCommand(Long assetId, String linkType, String note) { }

    public record ChangeDetail(ChangeRequest change, List<ChangeTask> tasks, List<ChangeAssetView> assets) { }

    public record ChangeAssetView(Long assetId, String linkType, String note, CmdbAsset asset,
                                  LocalDateTime createdAt) { }
}
