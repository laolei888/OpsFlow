package com.opsflow.postmortem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import com.opsflow.incident.IncidentStatus;
import com.opsflow.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostmortemService {
    private static final Set<IncidentStatus> REVIEWABLE_INCIDENT_STATUSES = Set.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentPostmortemMapper postmortemMapper;
    private final IncidentPostmortemActionMapper actionMapper;
    private final IncidentMapper incidentMapper;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public List<PostmortemListItem> list(Long tenantId, PostmortemStatus status, String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<IncidentPostmortem> query = new LambdaQueryWrapper<IncidentPostmortem>()
                .eq(IncidentPostmortem::getTenantId, tenantId)
                .orderByDesc(IncidentPostmortem::getUpdatedAt)
                .orderByDesc(IncidentPostmortem::getId)
                .last("LIMIT " + safeLimit);
        if (status != null) query.eq(IncidentPostmortem::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(IncidentPostmortem::getReviewNo, search)
                    .or().like(IncidentPostmortem::getTitle, search)
                    .or().like(IncidentPostmortem::getRootCause, search)
                    .or().like(IncidentPostmortem::getExecutiveSummary, search));
        }
        List<IncidentPostmortem> postmortems = postmortemMapper.selectList(query);
        if (postmortems.isEmpty()) return List.of();

        Map<Long, Incident> incidents = incidentMapper.selectList(new LambdaQueryWrapper<Incident>()
                        .eq(Incident::getTenantId, tenantId)
                        .in(Incident::getId, postmortems.stream().map(IncidentPostmortem::getIncidentId).toList()))
                .stream().collect(Collectors.toMap(Incident::getId, Function.identity()));
        Map<Long, List<IncidentPostmortemAction>> actions = actionMapper.selectList(
                        new LambdaQueryWrapper<IncidentPostmortemAction>()
                                .eq(IncidentPostmortemAction::getTenantId, tenantId)
                                .in(IncidentPostmortemAction::getPostmortemId,
                                        postmortems.stream().map(IncidentPostmortem::getId).toList()))
                .stream().collect(Collectors.groupingBy(IncidentPostmortemAction::getPostmortemId));
        LocalDateTime now = LocalDateTime.now();
        return postmortems.stream().map(postmortem -> {
            List<IncidentPostmortemAction> items = actions.getOrDefault(postmortem.getId(), List.of());
            long completed = items.stream().filter(item -> item.getStatus() == PostmortemActionStatus.DONE).count();
            long overdue = items.stream().filter(item -> isOverdue(item, now)).count();
            return new PostmortemListItem(postmortem, incidents.get(postmortem.getIncidentId()),
                    items.size(), completed, overdue);
        }).toList();
    }

    public PostmortemDetail detail(Long id, Long tenantId) {
        IncidentPostmortem postmortem = find(id, tenantId);
        Incident incident = findIncident(postmortem.getIncidentId(), tenantId);
        List<IncidentPostmortemAction> actions = actions(id, tenantId);
        return new PostmortemDetail(postmortem, incident, actions, readiness(postmortem, incident, actions));
    }

    @Transactional
    public IncidentPostmortem create(CreateCommand command, Long actorId, Long tenantId) {
        if (command == null || command.incidentId() == null) throw new BusinessException("必须选择需要复盘的重大事件");
        Incident incident = findIncident(command.incidentId(), tenantId);
        if (incident.getStatus() == IncidentStatus.CANCELLED) throw new BusinessException("已取消的重大事件不能创建复盘");
        IncidentPostmortem existing = postmortemMapper.selectOne(new LambdaQueryWrapper<IncidentPostmortem>()
                .eq(IncidentPostmortem::getTenantId, tenantId)
                .eq(IncidentPostmortem::getIncidentId, command.incidentId()));
        if (existing != null) throw new BusinessException("该重大事件已经创建复盘");

        Long facilitatorId = command.facilitatorId() == null ? actorId : command.facilitatorId();
        validateStaff(facilitatorId, tenantId, "复盘负责人");
        if (command.reviewerId() != null) validateStaff(command.reviewerId(), tenantId, "评审人");
        LocalDateTime now = LocalDateTime.now();
        IncidentPostmortem postmortem = new IncidentPostmortem();
        postmortem.setTenantId(tenantId);
        postmortem.setIncidentId(incident.getId());
        postmortem.setReviewNo("PIR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        postmortem.setTitle(required(command.title() == null ? incident.getTitle() + " · 事件复盘" : command.title(), "复盘标题", 160));
        postmortem.setStatus(PostmortemStatus.DRAFT);
        postmortem.setFacilitatorId(facilitatorId);
        postmortem.setReviewerId(command.reviewerId());
        postmortem.setDueAt(command.dueAt());
        postmortem.setCreatedBy(actorId);
        postmortem.setCreatedAt(now);
        postmortem.setUpdatedAt(now);
        try {
            postmortemMapper.insert(postmortem);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("该重大事件已经创建复盘");
        }
        auditLogService.record(tenantId, actorId, "POSTMORTEM", postmortem.getId(), "POSTMORTEM_CREATED",
                null, postmortem, "为重大事件 " + incident.getIncidentNo() + " 创建结构化复盘");
        return postmortem;
    }

    @Transactional
    public IncidentPostmortem update(Long id, UpdateCommand command, Long actorId, Long tenantId) {
        IncidentPostmortem postmortem = lock(id, tenantId);
        ensureDraft(postmortem);
        IncidentPostmortem before = copy(postmortem);
        postmortem.setTitle(required(command.title(), "复盘标题", 160));
        Long facilitatorId = command.facilitatorId() == null ? postmortem.getFacilitatorId() : command.facilitatorId();
        validateStaff(facilitatorId, tenantId, "复盘负责人");
        if (command.reviewerId() != null) validateStaff(command.reviewerId(), tenantId, "评审人");
        postmortem.setFacilitatorId(facilitatorId);
        postmortem.setReviewerId(command.reviewerId());
        postmortem.setExecutiveSummary(text(command.executiveSummary(), "执行摘要", 10000));
        postmortem.setCustomerImpact(text(command.customerImpact(), "客户影响", 10000));
        postmortem.setRootCause(text(command.rootCause(), "根因分析", 10000));
        postmortem.setContributingFactors(text(command.contributingFactors(), "促成因素", 10000));
        postmortem.setDetectionGap(text(command.detectionGap(), "发现缺口", 10000));
        postmortem.setResolutionSummary(text(command.resolutionSummary(), "解决过程", 10000));
        postmortem.setLessonsLearned(text(command.lessonsLearned(), "经验教训", 10000));
        postmortem.setPreventionPlan(text(command.preventionPlan(), "预防计划", 10000));
        postmortem.setDueAt(command.dueAt());
        postmortem.setUpdatedAt(LocalDateTime.now());
        postmortemMapper.updateById(postmortem);
        auditLogService.record(tenantId, actorId, "POSTMORTEM", id, "POSTMORTEM_UPDATED",
                before, postmortem, "更新结构化复盘内容");
        return postmortem;
    }

    @Transactional
    public IncidentPostmortemAction addAction(Long postmortemId, ActionCreateCommand command,
                                               Long actorId, Long tenantId) {
        IncidentPostmortem postmortem = lock(postmortemId, tenantId);
        ensureDraft(postmortem);
        if (command.ownerId() != null) validateStaff(command.ownerId(), tenantId, "整改负责人");
        LocalDateTime now = LocalDateTime.now();
        IncidentPostmortemAction action = new IncidentPostmortemAction();
        action.setTenantId(tenantId);
        action.setPostmortemId(postmortemId);
        action.setTitle(required(command.title(), "整改项标题", 180));
        action.setDescription(text(command.description(), "整改项说明", 2000));
        action.setOwnerId(command.ownerId());
        action.setPriority(command.priority() == null ? PostmortemActionPriority.MEDIUM : command.priority());
        action.setStatus(PostmortemActionStatus.OPEN);
        action.setDueAt(command.dueAt());
        action.setCreatedBy(actorId);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        actionMapper.insert(action);
        touch(postmortem, now);
        auditLogService.record(tenantId, actorId, "POSTMORTEM", postmortemId, "POSTMORTEM_ACTION_CREATED",
                null, action, "新增复盘整改行动项");
        return action;
    }

    @Transactional
    public IncidentPostmortemAction updateAction(Long actionId, ActionUpdateCommand command,
                                                  Long actorId, Long tenantId) {
        IncidentPostmortemAction action = actionMapper.selectForUpdate(actionId, tenantId);
        if (action == null) throw new BusinessException("整改行动项不存在");
        IncidentPostmortem postmortem = lock(action.getPostmortemId(), tenantId);
        IncidentPostmortemAction before = copy(action);
        boolean metadataChange = command.title() != null || command.description() != null || command.ownerId() != null
                || command.priority() != null || command.dueAt() != null;
        if (metadataChange && postmortem.getStatus() != PostmortemStatus.DRAFT) {
            throw new BusinessException("复盘提交后只能更新整改进度和完成证据");
        }
        if (command.title() != null) action.setTitle(required(command.title(), "整改项标题", 180));
        if (command.description() != null) action.setDescription(text(command.description(), "整改项说明", 2000));
        if (command.ownerId() != null) {
            validateStaff(command.ownerId(), tenantId, "整改负责人");
            action.setOwnerId(command.ownerId());
        }
        if (command.priority() != null) action.setPriority(command.priority());
        if (command.dueAt() != null) action.setDueAt(command.dueAt());
        if (command.status() != null && command.status() != action.getStatus()) {
            validateActionTransition(action.getStatus(), command.status());
            action.setStatus(command.status());
        }
        if (command.completionEvidence() != null) {
            action.setCompletionEvidence(text(command.completionEvidence(), "完成证据", 2000));
        }
        if (action.getStatus() == PostmortemActionStatus.DONE) {
            if (action.getCompletionEvidence() == null || action.getCompletionEvidence().isBlank()) {
                throw new BusinessException("完成整改项前必须填写验证证据");
            }
            action.setCompletedAt(action.getCompletedAt() == null ? LocalDateTime.now() : action.getCompletedAt());
        } else {
            action.setCompletedAt(null);
        }
        LocalDateTime now = LocalDateTime.now();
        action.setUpdatedAt(now);
        actionMapper.updateById(action);
        touch(postmortem, now);
        auditLogService.record(tenantId, actorId, "POSTMORTEM", postmortem.getId(), "POSTMORTEM_ACTION_UPDATED",
                before, action, "更新复盘整改行动项");
        return action;
    }

    @Transactional
    public void deleteAction(Long actionId, Long actorId, Long tenantId) {
        IncidentPostmortemAction action = actionMapper.selectForUpdate(actionId, tenantId);
        if (action == null) throw new BusinessException("整改行动项不存在");
        IncidentPostmortem postmortem = lock(action.getPostmortemId(), tenantId);
        ensureDraft(postmortem);
        actionMapper.deleteById(actionId);
        touch(postmortem, LocalDateTime.now());
        auditLogService.record(tenantId, actorId, "POSTMORTEM", postmortem.getId(), "POSTMORTEM_ACTION_DELETED",
                action, null, "删除复盘整改行动项");
    }

    @Transactional
    public IncidentPostmortem submit(Long id, Long actorId, Long tenantId) {
        IncidentPostmortem postmortem = lock(id, tenantId);
        ensureDraft(postmortem);
        Incident incident = findIncident(postmortem.getIncidentId(), tenantId);
        List<IncidentPostmortemAction> actions = actions(id, tenantId);
        Readiness readiness = readiness(postmortem, incident, actions);
        if (!readiness.readyForSubmit()) {
            throw new BusinessException("复盘尚未达到提交条件：" + String.join("、", readiness.missingItems()));
        }
        LocalDateTime now = LocalDateTime.now();
        IncidentPostmortem before = copy(postmortem);
        postmortem.setStatus(PostmortemStatus.IN_REVIEW);
        postmortem.setSubmittedAt(now);
        postmortem.setApprovedAt(null);
        postmortem.setPublishedAt(null);
        postmortem.setReviewComment(null);
        postmortem.setRejectedReason(null);
        postmortem.setUpdatedAt(now);
        postmortemMapper.updateById(postmortem);
        notificationService.notifyIfAbsent(tenantId, postmortem.getReviewerId(), "POSTMORTEM_REVIEW_REQUIRED",
                "重大事件复盘待评审", "「" + postmortem.getTitle() + "」已提交，请完成复盘评审。",
                "POSTMORTEM_REVIEW_REQUIRED:" + postmortem.getId() + ":" + postmortem.getSubmittedAt());
        auditLogService.record(tenantId, actorId, "POSTMORTEM", id, "POSTMORTEM_SUBMITTED",
                before, postmortem, "提交重大事件复盘评审");
        return postmortem;
    }

    @Transactional
    public IncidentPostmortem review(Long id, boolean approve, String comment, Long actorId, Long tenantId) {
        IncidentPostmortem postmortem = lock(id, tenantId);
        if (postmortem.getStatus() != PostmortemStatus.IN_REVIEW) throw new BusinessException("只有待评审复盘可以执行评审");
        String normalizedComment = text(comment, "评审意见", 1000);
        if (!approve && normalizedComment == null) throw new BusinessException("驳回复盘时必须填写原因");
        IncidentPostmortem before = copy(postmortem);
        LocalDateTime now = LocalDateTime.now();
        if (approve) {
            postmortem.setStatus(PostmortemStatus.APPROVED);
            postmortem.setApprovedAt(now);
            postmortem.setRejectedReason(null);
        } else {
            postmortem.setStatus(PostmortemStatus.DRAFT);
            postmortem.setSubmittedAt(null);
            postmortem.setApprovedAt(null);
            postmortem.setRejectedReason(normalizedComment);
        }
        postmortem.setReviewComment(normalizedComment);
        postmortem.setUpdatedAt(now);
        postmortemMapper.updateById(postmortem);
        notifyFacilitator(postmortem, approve ? "POSTMORTEM_APPROVED" : "POSTMORTEM_REJECTED",
                approve ? "重大事件复盘已通过" : "重大事件复盘需修改",
                approve ? "复盘已通过评审，可以发布并进入整改跟踪。" : "驳回原因：" + normalizedComment);
        auditLogService.record(tenantId, actorId, "POSTMORTEM", id,
                approve ? "POSTMORTEM_APPROVED" : "POSTMORTEM_REJECTED",
                before, postmortem, approve ? "批准重大事件复盘" : "驳回重大事件复盘：" + normalizedComment);
        return postmortem;
    }

    @Transactional
    public IncidentPostmortem publish(Long id, Long actorId, Long tenantId) {
        IncidentPostmortem postmortem = lock(id, tenantId);
        if (postmortem.getStatus() != PostmortemStatus.APPROVED) throw new BusinessException("只有已批准复盘可以发布");
        IncidentPostmortem before = copy(postmortem);
        LocalDateTime now = LocalDateTime.now();
        postmortem.setStatus(PostmortemStatus.PUBLISHED);
        postmortem.setPublishedAt(now);
        postmortem.setUpdatedAt(now);
        postmortemMapper.updateById(postmortem);
        notifyFacilitator(postmortem, "POSTMORTEM_PUBLISHED", "重大事件复盘已发布",
                "「" + postmortem.getTitle() + "」已发布，整改行动项将继续跟踪。 ");
        auditLogService.record(tenantId, actorId, "POSTMORTEM", id, "POSTMORTEM_PUBLISHED",
                before, postmortem, "发布重大事件复盘");
        return postmortem;
    }

    private Readiness readiness(IncidentPostmortem postmortem, Incident incident,
                                List<IncidentPostmortemAction> actions) {
        List<String> missing = new ArrayList<>();
        if (!REVIEWABLE_INCIDENT_STATUSES.contains(incident.getStatus())) missing.add("重大事件需先解决");
        if (postmortem.getReviewerId() == null) missing.add("指定评审人");
        if (blank(postmortem.getExecutiveSummary())) missing.add("执行摘要");
        if (blank(postmortem.getCustomerImpact())) missing.add("客户影响");
        if (blank(postmortem.getRootCause())) missing.add("根因分析");
        if (blank(postmortem.getResolutionSummary())) missing.add("解决过程");
        if (blank(postmortem.getLessonsLearned())) missing.add("经验教训");
        if (blank(postmortem.getPreventionPlan())) missing.add("预防计划");
        if (actions.isEmpty()) missing.add("至少一个整改行动项");
        if (actions.stream().anyMatch(action -> action.getOwnerId() == null)) missing.add("为全部整改项指定负责人");
        if (actions.stream().anyMatch(action -> action.getDueAt() == null)) missing.add("为全部整改项设置截止时间");
        LocalDateTime now = LocalDateTime.now();
        long completed = actions.stream().filter(action -> action.getStatus() == PostmortemActionStatus.DONE).count();
        long overdue = actions.stream().filter(action -> isOverdue(action, now)).count();
        return new Readiness(missing.isEmpty(), List.copyOf(missing), actions.size(), completed, overdue);
    }

    private List<IncidentPostmortemAction> actions(Long postmortemId, Long tenantId) {
        return actionMapper.selectList(new LambdaQueryWrapper<IncidentPostmortemAction>()
                .eq(IncidentPostmortemAction::getTenantId, tenantId)
                .eq(IncidentPostmortemAction::getPostmortemId, postmortemId)
                .orderByDesc(IncidentPostmortemAction::getPriority)
                .orderByAsc(IncidentPostmortemAction::getDueAt)
                .orderByAsc(IncidentPostmortemAction::getId));
    }

    private IncidentPostmortem find(Long id, Long tenantId) {
        IncidentPostmortem postmortem = postmortemMapper.selectOne(new LambdaQueryWrapper<IncidentPostmortem>()
                .eq(IncidentPostmortem::getId, id)
                .eq(IncidentPostmortem::getTenantId, tenantId));
        if (postmortem == null) throw new BusinessException("重大事件复盘不存在");
        return postmortem;
    }

    private IncidentPostmortem lock(Long id, Long tenantId) {
        IncidentPostmortem postmortem = postmortemMapper.selectForUpdate(id, tenantId);
        if (postmortem == null) throw new BusinessException("重大事件复盘不存在");
        return postmortem;
    }

    private Incident findIncident(Long id, Long tenantId) {
        Incident incident = incidentMapper.selectOne(new LambdaQueryWrapper<Incident>()
                .eq(Incident::getId, id)
                .eq(Incident::getTenantId, tenantId));
        if (incident == null) throw new BusinessException("重大事件不存在");
        return incident;
    }

    private void validateStaff(Long userId, Long tenantId, String field) {
        if (userId == null || !authService.isStaffMember(userId, tenantId)) {
            throw new BusinessException(field + "必须是当前租户的客服或管理员");
        }
    }

    private void ensureDraft(IncidentPostmortem postmortem) {
        if (postmortem.getStatus() != PostmortemStatus.DRAFT) throw new BusinessException("只有草稿复盘可以修改结构化内容");
    }

    private void validateActionTransition(PostmortemActionStatus current, PostmortemActionStatus target) {
        Map<PostmortemActionStatus, Set<PostmortemActionStatus>> allowed = new HashMap<>();
        allowed.put(PostmortemActionStatus.OPEN, Set.of(PostmortemActionStatus.IN_PROGRESS,
                PostmortemActionStatus.DONE, PostmortemActionStatus.CANCELLED));
        allowed.put(PostmortemActionStatus.IN_PROGRESS, Set.of(PostmortemActionStatus.OPEN,
                PostmortemActionStatus.DONE, PostmortemActionStatus.CANCELLED));
        allowed.put(PostmortemActionStatus.DONE, Set.of(PostmortemActionStatus.IN_PROGRESS));
        allowed.put(PostmortemActionStatus.CANCELLED, Set.of(PostmortemActionStatus.OPEN));
        if (!allowed.getOrDefault(current, Set.of()).contains(target)) {
            throw new BusinessException("不允许将整改项从 " + current + " 流转到 " + target);
        }
    }

    private void touch(IncidentPostmortem postmortem, LocalDateTime now) {
        postmortem.setUpdatedAt(now);
        postmortemMapper.updateById(postmortem);
    }

    private void notifyFacilitator(IncidentPostmortem postmortem, String type, String title, String content) {
        notificationService.notifyIfAbsent(postmortem.getTenantId(), postmortem.getFacilitatorId(), type,
                title, content, type + ":" + postmortem.getId() + ":" + postmortem.getUpdatedAt());
    }

    private boolean isOverdue(IncidentPostmortemAction action, LocalDateTime now) {
        return action.getDueAt() != null && action.getDueAt().isBefore(now)
                && action.getStatus() != PostmortemActionStatus.DONE
                && action.getStatus() != PostmortemActionStatus.CANCELLED;
    }

    private String required(String value, String field, int max) {
        String normalized = text(value, field, max);
        if (normalized == null) throw new BusinessException(field + "不能为空");
        return normalized;
    }

    private String text(String value, String field, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max) throw new BusinessException(field + "不能超过 " + max + " 个字符");
        return normalized;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private IncidentPostmortem copy(IncidentPostmortem source) {
        IncidentPostmortem result = new IncidentPostmortem();
        result.setId(source.getId()); result.setTenantId(source.getTenantId()); result.setIncidentId(source.getIncidentId());
        result.setReviewNo(source.getReviewNo()); result.setTitle(source.getTitle()); result.setStatus(source.getStatus());
        result.setFacilitatorId(source.getFacilitatorId()); result.setReviewerId(source.getReviewerId());
        result.setExecutiveSummary(source.getExecutiveSummary()); result.setCustomerImpact(source.getCustomerImpact());
        result.setRootCause(source.getRootCause()); result.setContributingFactors(source.getContributingFactors());
        result.setDetectionGap(source.getDetectionGap()); result.setResolutionSummary(source.getResolutionSummary());
        result.setLessonsLearned(source.getLessonsLearned()); result.setPreventionPlan(source.getPreventionPlan());
        result.setDueAt(source.getDueAt()); result.setSubmittedAt(source.getSubmittedAt());
        result.setApprovedAt(source.getApprovedAt()); result.setPublishedAt(source.getPublishedAt());
        result.setReviewComment(source.getReviewComment()); result.setRejectedReason(source.getRejectedReason());
        result.setCreatedBy(source.getCreatedBy()); result.setCreatedAt(source.getCreatedAt()); result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private IncidentPostmortemAction copy(IncidentPostmortemAction source) {
        IncidentPostmortemAction result = new IncidentPostmortemAction();
        result.setId(source.getId()); result.setTenantId(source.getTenantId()); result.setPostmortemId(source.getPostmortemId());
        result.setTitle(source.getTitle()); result.setDescription(source.getDescription()); result.setOwnerId(source.getOwnerId());
        result.setPriority(source.getPriority()); result.setStatus(source.getStatus()); result.setDueAt(source.getDueAt());
        result.setCompletionEvidence(source.getCompletionEvidence()); result.setCompletedAt(source.getCompletedAt());
        result.setCreatedBy(source.getCreatedBy()); result.setCreatedAt(source.getCreatedAt()); result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    public record CreateCommand(Long incidentId, String title, Long facilitatorId, Long reviewerId,
                                LocalDateTime dueAt) { }

    public record UpdateCommand(String title, Long facilitatorId, Long reviewerId, String executiveSummary,
                                String customerImpact, String rootCause, String contributingFactors,
                                String detectionGap, String resolutionSummary, String lessonsLearned,
                                String preventionPlan, LocalDateTime dueAt) { }

    public record ActionCreateCommand(String title, String description, Long ownerId,
                                      PostmortemActionPriority priority, LocalDateTime dueAt) { }

    public record ActionUpdateCommand(String title, String description, Long ownerId,
                                      PostmortemActionPriority priority, LocalDateTime dueAt,
                                      PostmortemActionStatus status, String completionEvidence) { }

    public record PostmortemListItem(IncidentPostmortem postmortem, Incident incident,
                                     long actionCount, long completedActionCount, long overdueActionCount) { }

    public record PostmortemDetail(IncidentPostmortem postmortem, Incident incident,
                                   List<IncidentPostmortemAction> actions, Readiness readiness) { }

    public record Readiness(boolean readyForSubmit, List<String> missingItems, long actionCount,
                            long completedActionCount, long overdueActionCount) { }
}
