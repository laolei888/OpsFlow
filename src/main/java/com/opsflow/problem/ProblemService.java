package com.opsflow.problem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.change.ChangeRequest;
import com.opsflow.change.ChangeRequestMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.messaging.AsyncNotificationPublisher;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
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
public class ProblemService {
    private static final Set<ProblemStatus> TERMINAL_STATUSES = Set.of(ProblemStatus.CLOSED, ProblemStatus.CANCELLED);
    private static final Set<String> TICKET_LINK_TYPES = Set.of("SYMPTOM", "RELATED");
    private static final Set<String> CHANGE_LINK_TYPES = Set.of("REMEDIATION", "RELATED");

    private final ProblemMapper problemMapper;
    private final ProblemTaskMapper taskMapper;
    private final ProblemTicketLinkMapper ticketLinkMapper;
    private final ProblemChangeLinkMapper changeLinkMapper;
    private final TicketMapper ticketMapper;
    private final ChangeRequestMapper changeMapper;
    private final TicketService ticketService;
    private final AuthService authService;
    private final AsyncNotificationPublisher notificationPublisher;
    private final AuditLogService auditLogService;

    public List<Problem> list(Long tenantId, Long requesterId, ProblemStatus status,
                              ProblemPriority priority, String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<Problem> query = new LambdaQueryWrapper<Problem>()
                .eq(Problem::getTenantId, tenantId)
                .orderByDesc(Problem::getCreatedAt)
                .orderByDesc(Problem::getId)
                .last("LIMIT " + safeLimit);
        if (requesterId != null) query.eq(Problem::getRequesterId, requesterId);
        if (status != null) query.eq(Problem::getStatus, status);
        if (priority != null) query.eq(Problem::getPriority, priority);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(Problem::getProblemNo, search)
                    .or().like(Problem::getTitle, search)
                    .or().like(Problem::getDescription, search)
                    .or().like(Problem::getRootCause, search));
        }
        return problemMapper.selectList(query);
    }

    public ProblemDetail detail(Long id, Long tenantId, Long requesterId) {
        Problem problem = findVisible(id, tenantId, requesterId);
        List<ProblemTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ProblemTask>()
                .eq(ProblemTask::getTenantId, tenantId)
                .eq(ProblemTask::getProblemId, id)
                .orderByAsc(ProblemTask::getSortOrder)
                .orderByAsc(ProblemTask::getId));

        List<ProblemTicketLink> ticketLinks = ticketLinkMapper.selectList(new LambdaQueryWrapper<ProblemTicketLink>()
                .eq(ProblemTicketLink::getTenantId, tenantId)
                .eq(ProblemTicketLink::getProblemId, id)
                .orderByDesc(ProblemTicketLink::getCreatedAt));
        List<ProblemChangeLink> changeLinks = changeLinkMapper.selectList(new LambdaQueryWrapper<ProblemChangeLink>()
                .eq(ProblemChangeLink::getTenantId, tenantId)
                .eq(ProblemChangeLink::getProblemId, id)
                .orderByDesc(ProblemChangeLink::getCreatedAt));

        Map<Long, Ticket> tickets = new HashMap<>();
        Set<Long> ticketIds = new HashSet<>();
        ticketLinks.forEach(link -> ticketIds.add(link.getTicketId()));
        if (!ticketIds.isEmpty()) {
            ticketMapper.selectBatchIds(ticketIds).forEach(ticket -> {
                if (tenantId.equals(ticket.getTenantId())) tickets.put(ticket.getId(), ticket);
            });
        }
        Map<Long, ChangeRequest> changes = new HashMap<>();
        Set<Long> changeIds = new HashSet<>();
        changeLinks.forEach(link -> changeIds.add(link.getChangeId()));
        if (!changeIds.isEmpty()) {
            changeMapper.selectBatchIds(changeIds).forEach(change -> {
                if (tenantId.equals(change.getTenantId())) changes.put(change.getId(), change);
            });
        }
        List<ProblemTicketView> ticketViews = ticketLinks.stream()
                .filter(link -> tickets.containsKey(link.getTicketId()))
                .map(link -> new ProblemTicketView(link.getTicketId(), link.getLinkType(), link.getNote(),
                        tickets.get(link.getTicketId()), link.getCreatedAt()))
                .toList();
        List<ProblemChangeView> changeViews = changeLinks.stream()
                .filter(link -> changes.containsKey(link.getChangeId()))
                .map(link -> new ProblemChangeView(link.getChangeId(), link.getLinkType(), link.getNote(),
                        changes.get(link.getChangeId()), link.getCreatedAt()))
                .toList();
        return new ProblemDetail(problem, tasks, ticketViews, changeViews);
    }

    @Transactional
    public Problem create(ProblemCommand command, String rawIdempotencyKey, Long requesterId, Long tenantId) {
        validateCommand(command);
        String idempotencyKey = normalizeKey(rawIdempotencyKey);
        String requestHash = requestHash(command);
        if (idempotencyKey != null) {
            Problem existing = problemMapper.selectOne(new LambdaQueryWrapper<Problem>()
                    .eq(Problem::getTenantId, tenantId)
                    .eq(Problem::getRequesterId, requesterId)
                    .eq(Problem::getRequestKey, idempotencyKey));
            if (existing != null) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new BusinessException("幂等键已用于其他问题申请");
                }
                return existing;
            }
        }
        validateAssignee(command.ownerId(), tenantId);

        LocalDateTime now = LocalDateTime.now();
        Problem problem = new Problem();
        problem.setTenantId(tenantId);
        problem.setProblemNo("PRB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        problem.setRequesterId(requesterId);
        problem.setOwnerId(command.ownerId());
        problem.setTitle(normalize(command.title()));
        problem.setDescription(normalize(command.description()));
        problem.setBusinessImpact(normalize(command.businessImpact()));
        problem.setPriority(command.priority());
        problem.setStatus(Boolean.TRUE.equals(command.knownError()) ? ProblemStatus.KNOWN_ERROR : ProblemStatus.OPEN);
        problem.setWorkaround(normalize(command.workaround()));
        problem.setKnownError(Boolean.TRUE.equals(command.knownError()));
        problem.setRequestKey(idempotencyKey);
        problem.setRequestHash(idempotencyKey == null ? null : requestHash);
        problem.setCreatedAt(now);
        problem.setUpdatedAt(now);
        try {
            problemMapper.insert(problem);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey != null) {
                Problem existing = problemMapper.selectOne(new LambdaQueryWrapper<Problem>()
                        .eq(Problem::getTenantId, tenantId)
                        .eq(Problem::getRequesterId, requesterId)
                        .eq(Problem::getRequestKey, idempotencyKey));
                if (existing != null && requestHash.equals(existing.getRequestHash())) return existing;
            }
            throw new BusinessException("问题申请提交失败，请稍后重试");
        }
        createTasks(problem, command.taskTitles(), tenantId, requesterId, now);
        auditLogService.record(tenantId, requesterId, "PROBLEM", problem.getId(), "PROBLEM_CREATED",
                null, problem, "创建问题单");
        notifyApprovers(problem);
        return problem;
    }

    @Transactional
    public Problem update(Long id, ProblemUpdateCommand command, Long actorId, Long tenantId) {
        Problem problem = lock(id, tenantId);
        ensureEditable(problem);
        validateAssignee(command.ownerId(), tenantId);
        Problem before = copy(problem);
        if (command.priority() != null) problem.setPriority(command.priority());
        if (command.rootCause() != null) problem.setRootCause(normalize(command.rootCause()));
        if (command.workaround() != null) problem.setWorkaround(normalize(command.workaround()));
        if (command.resolution() != null) problem.setResolution(normalize(command.resolution()));
        if (command.knownError() != null) {
            problem.setKnownError(command.knownError());
            if (command.knownError()) problem.setStatus(ProblemStatus.KNOWN_ERROR);
        }
        problem.setOwnerId(command.ownerId());
        problem.setUpdatedAt(LocalDateTime.now());
        problemMapper.updateById(problem);
        auditLogService.record(tenantId, actorId, "PROBLEM", id, "PROBLEM_UPDATED",
                before, problem, "更新问题分析资料");
        return problem;
    }

    @Transactional
    public Problem transition(Long id, ProblemStatus target, Long actorId, Long tenantId) {
        if (target == null) throw new BusinessException("目标状态不能为空");
        Problem problem = lock(id, tenantId);
        ProblemStatus before = problem.getStatus();
        validateTransition(before, target);
        if ((target == ProblemStatus.RESOLVED || target == ProblemStatus.CLOSED)
                && taskMapper.selectCount(new LambdaQueryWrapper<ProblemTask>()
                .eq(ProblemTask::getTenantId, tenantId)
                .eq(ProblemTask::getProblemId, id)
                .ne(ProblemTask::getStatus, ProblemTaskStatus.DONE)) > 0) {
            throw new BusinessException("仍有未完成的问题分析任务，不能结束问题单");
        }
        LocalDateTime now = LocalDateTime.now();
        problem.setStatus(target);
        if (target == ProblemStatus.KNOWN_ERROR) problem.setKnownError(true);
        if (target == ProblemStatus.OPEN || target == ProblemStatus.INVESTIGATING) problem.setResolvedAt(null);
        if (target == ProblemStatus.RESOLVED || target == ProblemStatus.CLOSED) problem.setResolvedAt(now);
        problem.setUpdatedAt(now);
        if (problemMapper.updateById(problem) == 0) throw new BusinessException("问题状态更新失败，请刷新后重试");
        auditLogService.record(tenantId, actorId, "PROBLEM", id, "PROBLEM_STATUS_CHANGED",
                Map.of("status", before.name()), Map.of("status", target.name()), "问题状态流转");
        notifyRequester(problem, "PROBLEM_STATUS_CHANGED", "问题单状态已更新",
                "「" + problem.getTitle() + "」当前状态：" + target.name(),
                "PROBLEM_STATUS_CHANGED:" + id + ":" + target.name());
        return problem;
    }

    @Transactional
    public ProblemTask createTask(Long problemId, TaskCommand command, Long tenantId, Long actorId) {
        Problem problem = find(problemId, tenantId);
        ensureEditable(problem);
        String title = normalize(command.title());
        if (title == null || title.length() > 160) throw new BusinessException("分析任务名称不能为空且不能超过 160 个字符");
        validateAssignee(command.assigneeId(), tenantId);
        LocalDateTime now = LocalDateTime.now();
        ProblemTask task = new ProblemTask();
        task.setTenantId(tenantId);
        task.setProblemId(problemId);
        task.setTitle(title);
        task.setAssigneeId(command.assigneeId());
        task.setSortOrder(command.sortOrder() == null ? 100 : Math.max(0, Math.min(command.sortOrder(), 9999)));
        task.setStatus(ProblemTaskStatus.TODO);
        task.setCreatedBy(actorId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        auditLogService.record(tenantId, actorId, "PROBLEM", problemId, "PROBLEM_TASK_CREATED",
                null, task, "新增问题分析任务");
        return task;
    }

    @Transactional
    public ProblemTask updateTask(Long taskId, TaskUpdateCommand command, Long tenantId, Long actorId) {
        ProblemTask task = taskMapper.selectOne(new LambdaQueryWrapper<ProblemTask>()
                .eq(ProblemTask::getId, taskId).eq(ProblemTask::getTenantId, tenantId));
        if (task == null) throw new BusinessException("问题分析任务不存在");
        Problem problem = find(task.getProblemId(), tenantId);
        ensureEditable(problem);
        ProblemTask before = copy(task);
        if (command.title() != null) {
            String title = normalize(command.title());
            if (title == null || title.length() > 160) throw new BusinessException("分析任务名称不能为空且不能超过 160 个字符");
            task.setTitle(title);
        }
        if (command.assigneeId() != null) {
            validateAssignee(command.assigneeId(), tenantId);
            task.setAssigneeId(command.assigneeId());
        }
        if (command.status() != null) {
            task.setStatus(command.status());
            task.setCompletedAt(command.status() == ProblemTaskStatus.DONE ? LocalDateTime.now() : null);
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        auditLogService.record(tenantId, actorId, "PROBLEM", problem.getId(), "PROBLEM_TASK_UPDATED",
                before, task, "更新问题分析任务");
        return task;
    }

    @Transactional
    public ProblemTicketView linkTicket(Long problemId, LinkCommand command, Long tenantId, Long actorId) {
        Problem problem = find(problemId, tenantId);
        ensureEditable(problem);
        Ticket ticket = ticketService.getVisible(command.targetId(), tenantId, null);
        String linkType = normalizeLinkType(command.linkType(), TICKET_LINK_TYPES, "工单");
        ProblemTicketLink existing = ticketLinkMapper.selectOne(new LambdaQueryWrapper<ProblemTicketLink>()
                .eq(ProblemTicketLink::getTenantId, tenantId)
                .eq(ProblemTicketLink::getProblemId, problemId)
                .eq(ProblemTicketLink::getTicketId, ticket.getId()));
        if (existing != null) return new ProblemTicketView(ticket.getId(), existing.getLinkType(), existing.getNote(),
                ticket, existing.getCreatedAt());
        ProblemTicketLink link = new ProblemTicketLink();
        link.setTenantId(tenantId);
        link.setProblemId(problemId);
        link.setTicketId(ticket.getId());
        link.setLinkType(linkType);
        link.setNote(normalize(command.note()));
        link.setCreatedBy(actorId);
        link.setCreatedAt(LocalDateTime.now());
        ticketLinkMapper.insert(link);
        ProblemTicketView view = new ProblemTicketView(ticket.getId(), link.getLinkType(), link.getNote(), ticket, link.getCreatedAt());
        auditLogService.record(tenantId, actorId, "PROBLEM", problemId, "PROBLEM_TICKET_LINKED",
                null, view, "关联问题症状工单");
        return view;
    }

    @Transactional
    public void unlinkTicket(Long problemId, Long ticketId, Long tenantId, Long actorId) {
        unlinkTicketOrThrow(problemId, ticketId, tenantId);
        auditLogService.record(tenantId, actorId, "PROBLEM", problemId, "PROBLEM_TICKET_UNLINKED",
                Map.of("ticketId", ticketId), null, "解除问题工单关联");
    }

    @Transactional
    public ProblemChangeView linkChange(Long problemId, LinkCommand command, Long tenantId, Long actorId) {
        Problem problem = find(problemId, tenantId);
        ensureEditable(problem);
        ChangeRequest change = changeMapper.selectOne(new LambdaQueryWrapper<ChangeRequest>()
                .eq(ChangeRequest::getId, command.targetId())
                .eq(ChangeRequest::getTenantId, tenantId));
        if (change == null) throw new BusinessException("关联的变更申请不存在");
        String linkType = normalizeLinkType(command.linkType(), CHANGE_LINK_TYPES, "变更");
        ProblemChangeLink existing = changeLinkMapper.selectOne(new LambdaQueryWrapper<ProblemChangeLink>()
                .eq(ProblemChangeLink::getTenantId, tenantId)
                .eq(ProblemChangeLink::getProblemId, problemId)
                .eq(ProblemChangeLink::getChangeId, change.getId()));
        if (existing != null) return new ProblemChangeView(change.getId(), existing.getLinkType(), existing.getNote(),
                change, existing.getCreatedAt());
        ProblemChangeLink link = new ProblemChangeLink();
        link.setTenantId(tenantId);
        link.setProblemId(problemId);
        link.setChangeId(change.getId());
        link.setLinkType(linkType);
        link.setNote(normalize(command.note()));
        link.setCreatedBy(actorId);
        link.setCreatedAt(LocalDateTime.now());
        changeLinkMapper.insert(link);
        ProblemChangeView view = new ProblemChangeView(change.getId(), link.getLinkType(), link.getNote(), change, link.getCreatedAt());
        auditLogService.record(tenantId, actorId, "PROBLEM", problemId, "PROBLEM_CHANGE_LINKED",
                null, view, "关联问题修复变更");
        return view;
    }

    @Transactional
    public void unlinkChange(Long problemId, Long changeId, Long tenantId, Long actorId) {
        Problem problem = find(problemId, tenantId);
        ensureEditable(problem);
        ProblemChangeLink link = changeLinkMapper.selectOne(new LambdaQueryWrapper<ProblemChangeLink>()
                .eq(ProblemChangeLink::getTenantId, tenantId)
                .eq(ProblemChangeLink::getProblemId, problemId)
                .eq(ProblemChangeLink::getChangeId, changeId));
        if (link == null) throw new BusinessException("问题尚未关联该变更");
        changeLinkMapper.delete(new LambdaQueryWrapper<ProblemChangeLink>()
                .eq(ProblemChangeLink::getTenantId, tenantId)
                .eq(ProblemChangeLink::getProblemId, problemId)
                .eq(ProblemChangeLink::getChangeId, changeId));
        auditLogService.record(tenantId, actorId, "PROBLEM", problemId, "PROBLEM_CHANGE_UNLINKED",
                Map.of("changeId", changeId), null, "解除问题变更关联");
    }

    private void unlinkTicketOrThrow(Long problemId, Long ticketId, Long tenantId) {
        Problem problem = find(problemId, tenantId);
        ensureEditable(problem);
        ProblemTicketLink link = ticketLinkMapper.selectOne(new LambdaQueryWrapper<ProblemTicketLink>()
                .eq(ProblemTicketLink::getTenantId, tenantId)
                .eq(ProblemTicketLink::getProblemId, problemId)
                .eq(ProblemTicketLink::getTicketId, ticketId));
        if (link == null) throw new BusinessException("问题尚未关联该工单");
        ticketLinkMapper.delete(new LambdaQueryWrapper<ProblemTicketLink>()
                .eq(ProblemTicketLink::getTenantId, tenantId)
                .eq(ProblemTicketLink::getProblemId, problemId)
                .eq(ProblemTicketLink::getTicketId, ticketId));
    }

    private void validateCommand(ProblemCommand command) {
        String title = normalize(command.title());
        String description = normalize(command.description());
        if (title == null || title.length() > 160) throw new BusinessException("问题标题不能为空且不能超过 160 个字符");
        if (description == null || description.length() > 10000) throw new BusinessException("问题描述不能为空且不能超过 10000 个字符");
        if (command.priority() == null) throw new BusinessException("请选择问题优先级");
        if (command.businessImpact() != null && command.businessImpact().trim().length() > 1000) throw new BusinessException("业务影响不能超过 1000 个字符");
        if (command.workaround() != null && command.workaround().trim().length() > 10000) throw new BusinessException("规避方案不能超过 10000 个字符");
        if (command.taskTitles() != null && command.taskTitles().size() > 30) throw new BusinessException("单个问题最多 30 个分析任务");
    }

    private void validateTransition(ProblemStatus before, ProblemStatus target) {
        boolean allowed = switch (before) {
            case OPEN -> target == ProblemStatus.INVESTIGATING || target == ProblemStatus.CANCELLED;
            case INVESTIGATING -> target == ProblemStatus.KNOWN_ERROR || target == ProblemStatus.PENDING_CHANGE
                    || target == ProblemStatus.RESOLVED || target == ProblemStatus.CANCELLED;
            case KNOWN_ERROR -> target == ProblemStatus.PENDING_CHANGE || target == ProblemStatus.RESOLVED
                    || target == ProblemStatus.CLOSED || target == ProblemStatus.CANCELLED;
            case PENDING_CHANGE -> target == ProblemStatus.RESOLVED || target == ProblemStatus.INVESTIGATING
                    || target == ProblemStatus.CANCELLED;
            case RESOLVED -> target == ProblemStatus.CLOSED || target == ProblemStatus.OPEN;
            case CLOSED, CANCELLED -> false;
        };
        if (!allowed) throw new BusinessException("不允许从「" + before + "」流转到「" + target + "」");
    }

    private void createTasks(Problem problem, List<String> titles, Long tenantId, Long actorId, LocalDateTime now) {
        if (titles == null) return;
        int order = 10;
        for (String rawTitle : titles) {
            String title = normalize(rawTitle);
            if (title == null) continue;
            if (title.length() > 160) throw new BusinessException("分析任务名称不能超过 160 个字符");
            ProblemTask task = new ProblemTask();
            task.setTenantId(tenantId);
            task.setProblemId(problem.getId());
            task.setTitle(title);
            task.setSortOrder(order);
            task.setStatus(ProblemTaskStatus.TODO);
            task.setCreatedBy(actorId);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            taskMapper.insert(task);
            order += 10;
        }
    }

    private void ensureEditable(Problem problem) {
        if (TERMINAL_STATUSES.contains(problem.getStatus())) throw new BusinessException("当前问题状态不允许修改");
    }

    private void validateAssignee(Long assigneeId, Long tenantId) {
        if (assigneeId != null && !authService.isStaffMember(assigneeId, tenantId)) {
            throw new BusinessException("问题负责人不存在或不是当前租户的客服账号");
        }
    }

    private void notifyApprovers(Problem problem) {
        for (StaffSummary member : authService.staffMembers(problem.getTenantId())) {
            if (member.roles().contains("ADMIN")) {
                notificationPublisher.enqueue(problem.getTenantId(), member.id(), null,
                        "PROBLEM_CREATED", "新的问题单",
                        "问题「" + problem.getTitle() + "」需要进行根因分析。",
                        "PROBLEM_CREATED:" + problem.getId(), "PROBLEM", problem.getId());
            }
        }
    }

    private void notifyRequester(Problem problem, String type, String title, String content, String dedupeKey) {
        notificationPublisher.enqueue(problem.getTenantId(), problem.getRequesterId(), null,
                type, title, content, dedupeKey, "PROBLEM", problem.getId());
    }

    private Problem lock(Long id, Long tenantId) {
        Problem problem = problemMapper.selectForUpdate(id, tenantId);
        if (problem == null) throw new BusinessException("问题单不存在");
        return problem;
    }

    private Problem find(Long id, Long tenantId) {
        Problem problem = problemMapper.selectOne(new LambdaQueryWrapper<Problem>()
                .eq(Problem::getId, id).eq(Problem::getTenantId, tenantId));
        if (problem == null) throw new BusinessException("问题单不存在");
        return problem;
    }

    private Problem findVisible(Long id, Long tenantId, Long requesterId) {
        Problem problem = find(id, tenantId);
        if (requesterId != null && !requesterId.equals(problem.getRequesterId())) throw new BusinessException("问题单不存在");
        return problem;
    }

    private String normalizeLinkType(String raw, Set<String> allowed, String resource) {
        String linkType = raw == null || raw.isBlank() ? allowed.iterator().next() : raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(linkType)) throw new BusinessException("无效的问题" + resource + "关联类型");
        return linkType;
    }

    private String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim();
        if (key.length() > 128) throw new BusinessException("Idempotency-Key 不能超过 128 个字符");
        return key;
    }

    private String requestHash(ProblemCommand command) {
        String source = String.join("\u0000", value(command.title()), value(command.description()),
                command.priority() == null ? "" : command.priority().name(), value(command.businessImpact()),
                value(command.workaround()), Boolean.TRUE.equals(command.knownError()) ? "1" : "0");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private Problem copy(Problem source) {
        Problem copy = new Problem();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setProblemNo(source.getProblemNo());
        copy.setRequesterId(source.getRequesterId());
        copy.setOwnerId(source.getOwnerId());
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setBusinessImpact(source.getBusinessImpact());
        copy.setPriority(source.getPriority());
        copy.setStatus(source.getStatus());
        copy.setRootCause(source.getRootCause());
        copy.setWorkaround(source.getWorkaround());
        copy.setKnownError(source.getKnownError());
        copy.setResolution(source.getResolution());
        copy.setRequestKey(source.getRequestKey());
        copy.setRequestHash(source.getRequestHash());
        copy.setResolvedAt(source.getResolvedAt());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private ProblemTask copy(ProblemTask source) {
        ProblemTask copy = new ProblemTask();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setProblemId(source.getProblemId());
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

    private String value(String value) { return value == null ? "" : value.trim(); }

    private String normalize(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    public record ProblemCommand(String title, String description, String businessImpact,
                                 ProblemPriority priority, String workaround, Boolean knownError,
                                 Long ownerId, List<String> taskTitles) { }

    public record ProblemUpdateCommand(ProblemPriority priority, String rootCause, String workaround,
                                       String resolution, Boolean knownError, Long ownerId) { }

    public record TaskCommand(String title, Long assigneeId, Integer sortOrder) { }

    public record TaskUpdateCommand(String title, Long assigneeId, ProblemTaskStatus status) { }

    public record LinkCommand(Long targetId, String linkType, String note) { }

    public record ProblemDetail(Problem problem, List<ProblemTask> tasks,
                                List<ProblemTicketView> tickets, List<ProblemChangeView> changes) { }

    public record ProblemTicketView(Long ticketId, String linkType, String note,
                                    Ticket ticket, LocalDateTime createdAt) { }

    public record ProblemChangeView(Long changeId, String linkType, String note,
                                    ChangeRequest change, LocalDateTime createdAt) { }
}
