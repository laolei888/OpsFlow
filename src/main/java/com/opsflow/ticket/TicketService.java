package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.opsflow.audit.AuditLogService;
import com.opsflow.automation.AutomationRuleService;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
import com.opsflow.sla.SlaPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketService {
    private final TicketMapper ticketMapper;
    private final AuditLogService auditLogService;
    private final SlaPolicyService slaPolicyService;
    private final AutomationRuleService automationRuleService;
    private final TicketIdempotencyKeyMapper idempotencyKeyMapper;
    private final NotificationService notificationService;

    public Page<Ticket> page(long page, long size, TicketStatus status, Long tenantId) {
        return page(page, size, status, null, null, false, null, null, tenantId, TicketSort.CREATED_DESC);
    }

    public Page<Ticket> page(long page, long size, TicketStatus status, TicketPriority priority,
                             String keyword, boolean overdue, Long tenantId) {
        return page(page, size, status, priority, keyword, overdue, null, null, tenantId,
                TicketSort.CREATED_DESC);
    }

    public Page<Ticket> page(long page, long size, TicketStatus status, TicketPriority priority,
                             String keyword, boolean overdue, Long assigneeId, Long tenantId) {
        return page(page, size, status, priority, keyword, overdue, assigneeId, null, tenantId,
                TicketSort.CREATED_DESC);
    }

    public Page<Ticket> page(long page, long size, TicketStatus status, TicketPriority priority,
                             String keyword, boolean overdue, Long assigneeId, Long requesterId,
                             Long tenantId) {
        return page(page, size, status, priority, keyword, overdue, assigneeId, requesterId,
                tenantId, TicketSort.CREATED_DESC);
    }

    public Page<Ticket> page(long page, long size, TicketStatus status, TicketPriority priority,
                             String keyword, boolean overdue, Long assigneeId, Long requesterId,
                             Long tenantId, TicketSort sort) {
        LambdaQueryWrapper<Ticket> query = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId);
        if (status != null) query.eq(Ticket::getStatus, status);
        if (priority != null) query.eq(Ticket::getPriority, priority);
        if (assigneeId != null) query.eq(Ticket::getAssigneeId, assigneeId);
        if (requesterId != null) query.eq(Ticket::getRequesterId, requesterId);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(Ticket::getTitle, search)
                    .or().like(Ticket::getTicketNo, search)
                    .or().like(Ticket::getDescription, search));
        }
        if (overdue) {
            query.in(Ticket::getStatus, TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION)
                    .isNotNull(Ticket::getDueAt)
                    .lt(Ticket::getDueAt, LocalDateTime.now());
        }
        applySort(query, sort);
        return ticketMapper.selectPage(new Page<>(page, Math.min(size, 100)), query);
    }

    private void applySort(LambdaQueryWrapper<Ticket> query, TicketSort sort) {
        TicketSort selected = sort == null ? TicketSort.CREATED_DESC : sort;
        switch (selected) {
            case PRIORITY_DESC -> query.last("ORDER BY CASE priority WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END DESC, due_at IS NULL ASC, due_at ASC, created_at DESC, id DESC");
            case SLA_ASC -> query.last("ORDER BY due_at IS NULL ASC, due_at ASC, created_at DESC, id DESC");
            case UPDATED_DESC -> query.orderByDesc(Ticket::getUpdatedAt).orderByDesc(Ticket::getId);
            case CREATED_ASC -> query.orderByAsc(Ticket::getCreatedAt).orderByAsc(Ticket::getId);
            case CREATED_DESC -> query.orderByDesc(Ticket::getCreatedAt).orderByDesc(Ticket::getId);
        }
    }
    public List<Ticket> export(TicketStatus status, TicketPriority priority, String keyword,
                               boolean overdue, Long assigneeId, Long requesterId,
                               Long tenantId, Integer limit) {
        return exportWithIds(null, status, priority, keyword, overdue, assigneeId, requesterId, tenantId, limit);
    }

    public List<Ticket> exportByIds(Set<Long> ticketIds, TicketStatus status, TicketPriority priority,
                                    String keyword, boolean overdue, Long assigneeId, Long requesterId,
                                    Long tenantId, Integer limit) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new BusinessException("至少选择一张工单");
        }
        if (ticketIds.size() > 200) {
            throw new BusinessException("单次最多选择 200 张工单导出");
        }
        return exportWithIds(ticketIds, status, priority, keyword, overdue, assigneeId, requesterId, tenantId, limit);
    }

    private List<Ticket> exportWithIds(Set<Long> ticketIds, TicketStatus status, TicketPriority priority,
                                       String keyword, boolean overdue, Long assigneeId, Long requesterId,
                                       Long tenantId, Integer limit) {
        int safeLimit = limit == null ? 5000 : Math.max(1, Math.min(limit, 5000));
        LambdaQueryWrapper<Ticket> query = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .orderByDesc(Ticket::getCreatedAt)
                .last("LIMIT " + safeLimit);
        if (ticketIds != null) query.in(Ticket::getId, ticketIds);
        if (status != null) query.eq(Ticket::getStatus, status);
        if (priority != null) query.eq(Ticket::getPriority, priority);
        if (assigneeId != null) query.eq(Ticket::getAssigneeId, assigneeId);
        if (requesterId != null) query.eq(Ticket::getRequesterId, requesterId);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(Ticket::getTitle, search)
                    .or().like(Ticket::getTicketNo, search)
                    .or().like(Ticket::getDescription, search));
        }
        if (overdue) {
            query.in(Ticket::getStatus, TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION)
                    .isNotNull(Ticket::getDueAt)
                    .lt(Ticket::getDueAt, LocalDateTime.now());
        }
        return ticketMapper.selectList(query);
    }

    public SlaSummary slaSummary(Long tenantId) {
        return slaSummary(null, tenantId);
    }

    public SlaSummary slaSummary(Long requesterId, Long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        List<TicketStatus> activeStatuses = List.of(
                TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION);
        LambdaQueryWrapper<Ticket> active = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .in(Ticket::getStatus, activeStatuses);
        if (requesterId != null) active.eq(Ticket::getRequesterId, requesterId);
        long activeCount = ticketMapper.selectCount(active);

        LambdaQueryWrapper<Ticket> overdue = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .in(Ticket::getStatus, activeStatuses)
                .isNotNull(Ticket::getDueAt)
                .lt(Ticket::getDueAt, now);
        if (requesterId != null) overdue.eq(Ticket::getRequesterId, requesterId);
        long overdueCount = ticketMapper.selectCount(overdue);

        LambdaQueryWrapper<Ticket> dueSoon = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .in(Ticket::getStatus, activeStatuses)
                .isNotNull(Ticket::getDueAt)
                .ge(Ticket::getDueAt, now)
                .le(Ticket::getDueAt, now.plusHours(24));
        if (requesterId != null) dueSoon.eq(Ticket::getRequesterId, requesterId);
        long dueSoonCount = ticketMapper.selectCount(dueSoon);
        return new SlaSummary(activeCount, overdueCount, dueSoonCount, activeCount - overdueCount - dueSoonCount);
    }

    @Transactional
    public Ticket create(Ticket ticket, Long requesterId, Long tenantId) {
        return create(ticket, requesterId, tenantId, null);
    }

    @Transactional
    public Ticket create(Ticket ticket, Long requesterId, Long tenantId, String rawIdempotencyKey) {
        TicketPriority normalizedPriority = ticket.getPriority() == null ? TicketPriority.MEDIUM : ticket.getPriority();
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        TicketIdempotencyKey reservation = null;
        if (idempotencyKey != null) {
            String requestHash = requestHash(ticket, normalizedPriority);
            Ticket existing = findIdempotentTicket(idempotencyKey, requestHash, requesterId, tenantId);
            if (existing != null) return existing;

            reservation = new TicketIdempotencyKey();
            reservation.setTenantId(tenantId);
            reservation.setRequesterId(requesterId);
            reservation.setIdempotencyKey(idempotencyKey);
            reservation.setRequestHash(requestHash);
            reservation.setCreatedAt(LocalDateTime.now());
            try {
                idempotencyKeyMapper.insert(reservation);
            } catch (DataIntegrityViolationException ex) {
                Ticket duplicate = findIdempotentTicket(idempotencyKey, requestHash, requesterId, tenantId);
                if (duplicate != null) return duplicate;
                throw new BusinessException("相同幂等请求正在处理中，请稍后重试");
            }
        }

        ticket.setTicketNo("OPF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        ticket.setTenantId(tenantId);
        ticket.setRequesterId(requesterId);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority(normalizedPriority);
        ticket.setVersion(0);
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setDueAt(ticket.getDueAt() == null
                ? slaPolicyService.dueAt(tenantId, ticket.getPriority(), ticket.getCreatedAt())
                : ticket.getDueAt());
        ticket.setUpdatedAt(ticket.getCreatedAt());
        ticketMapper.insert(ticket);
        auditLogService.record(tenantId, requesterId, "TICKET", ticket.getId(), "CREATED",
                null, Map.of("status", ticket.getStatus().name(), "priority", ticket.getPriority().name(),
                        "dueAt", ticket.getDueAt().toString()), "创建工单");
        automationRuleService.applyOnCreate(ticket, requesterId);
        if (reservation != null) {
            reservation.setTicketId(ticket.getId());
            idempotencyKeyMapper.updateById(reservation);
        }
        return ticket;
    }

    private Ticket findIdempotentTicket(String key, String requestHash, Long requesterId, Long tenantId) {
        TicketIdempotencyKey existing = idempotencyKeyMapper.selectOne(new LambdaQueryWrapper<TicketIdempotencyKey>()
                .eq(TicketIdempotencyKey::getTenantId, tenantId)
                .eq(TicketIdempotencyKey::getRequesterId, requesterId)
                .eq(TicketIdempotencyKey::getIdempotencyKey, key));
        if (existing == null) return null;
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new BusinessException("幂等键已用于其他工单请求");
        }
        if (existing.getTicketId() == null) {
            throw new BusinessException("相同幂等请求正在处理中，请稍后重试");
        }
        return get(existing.getTicketId(), tenantId);
    }

    private String normalizeIdempotencyKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        String key = rawKey.trim();
        if (key.length() > 128) throw new BusinessException("Idempotency-Key 不能超过 128 个字符");
        return key;
    }

    private String requestHash(Ticket ticket, TicketPriority priority) {
        String source = String.join("\u0000",
                value(ticket.getTitle()), value(ticket.getDescription()), value(ticket.getCategory()), priority.name());
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

    @Transactional
    public Ticket updateDetails(Long id, String title, String description, String category,
                                TicketPriority priority, Long actorId, Long tenantId) {
        Ticket ticket = get(id, tenantId);
        String normalizedTitle = requiredText(title, "标题", 160);
        String normalizedDescription = requiredText(description, "描述", 10000);
        String normalizedCategory = optionalText(category, "分类", 64);
        TicketPriority normalizedPriority = priority == null ? TicketPriority.MEDIUM : priority;

        Map<String, Object> before = ticketSnapshot(ticket);
        boolean changed = !Objects.equals(ticket.getTitle(), normalizedTitle)
                || !Objects.equals(ticket.getDescription(), normalizedDescription)
                || !Objects.equals(ticket.getCategory(), normalizedCategory)
                || ticket.getPriority() != normalizedPriority;
        if (!changed) return ticket;

        ticket.setTitle(normalizedTitle);
        ticket.setDescription(normalizedDescription);
        ticket.setCategory(normalizedCategory);
        ticket.setPriority(normalizedPriority);
        ticket.setUpdatedAt(LocalDateTime.now());
        if (ticketMapper.updateById(ticket) == 0) {
            throw new BusinessException("工单已被其他人更新，请刷新后重试");
        }

        auditLogService.record(tenantId, actorId, "TICKET", ticket.getId(), "TICKET_UPDATED",
                before, ticketSnapshot(ticket), "编辑工单信息");
        return ticket;
    }
    @Transactional
    public Ticket transition(Long id, TicketStatus target, Long tenantId) {
        return transition(id, target, null, tenantId);
    }

    @Transactional
    public BatchUpdateResult batchUpdate(List<Long> ticketIds, TicketStatus targetStatus,
                                         TicketPriority targetPriority, Long actorId, Long tenantId) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new BusinessException("至少选择一张工单");
        }
        if (targetStatus == null && targetPriority == null) {
            throw new BusinessException("至少选择一个批量更新项");
        }

        Set<Long> distinctIds = new LinkedHashSet<>(ticketIds);
        int updated = 0;
        for (Long id : distinctIds) {
            Ticket ticket = get(id, tenantId);
            TicketStatus beforeStatus = ticket.getStatus();
            TicketPriority beforePriority = ticket.getPriority();
            boolean statusChanged = targetStatus != null && beforeStatus != targetStatus;
            boolean priorityChanged = targetPriority != null && beforePriority != targetPriority;

            if (!statusChanged && !priorityChanged) continue;
            if (statusChanged && !isAllowed(beforeStatus, targetStatus)) {
                throw new BusinessException("工单 " + ticket.getTicketNo() + " 不允许从 "
                        + beforeStatus + " 流转到 " + targetStatus);
            }

            if (statusChanged) ticket.setStatus(targetStatus);
            if (priorityChanged) ticket.setPriority(targetPriority);
            ticket.setUpdatedAt(LocalDateTime.now());
            if (ticketMapper.updateById(ticket) == 0) {
                throw new BusinessException("工单 " + ticket.getTicketNo() + " 已被其他人更新，请刷新后重试");
            }

            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", beforeStatus == null ? null : beforeStatus.name());
            before.put("priority", beforePriority == null ? null : beforePriority.name());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", ticket.getStatus() == null ? null : ticket.getStatus().name());
            after.put("priority", ticket.getPriority() == null ? null : ticket.getPriority().name());
            auditLogService.record(tenantId, actorId, "TICKET", ticket.getId(), "BULK_UPDATED",
                    before, after, "批量更新工单属性");
            if (statusChanged && targetStatus == TicketStatus.RESOLVED) {
                requestSatisfaction(ticket);
            }
            updated++;
        }
        return new BatchUpdateResult(updated, targetStatus, targetPriority);
    }

    @Transactional
    public Ticket transition(Long id, TicketStatus target, Long actorId, Long tenantId) {
        Ticket ticket = get(id, tenantId);
        TicketStatus beforeStatus = ticket.getStatus();
        if (!isAllowed(beforeStatus, target)) {
            throw new BusinessException("不允许从 " + beforeStatus + " 流转到 " + target);
        }
        ticket.setStatus(target);
        ticket.setUpdatedAt(LocalDateTime.now());
        if (ticketMapper.updateById(ticket) == 0) {
            throw new BusinessException("工单已被其他人更新，请刷新后重试");
        }
        auditLogService.record(tenantId, actorId, "TICKET", ticket.getId(), "STATUS_CHANGED",
                Map.of("status", beforeStatus.name()), Map.of("status", target.name()),
                "工单状态从 " + beforeStatus + " 流转到 " + target);
        if (target == TicketStatus.RESOLVED) requestSatisfaction(ticket);
        return ticket;
    }

    private void requestSatisfaction(Ticket ticket) {
        if (ticket.getRequesterId() == null) return;
        notificationService.notifyIfAbsent(ticket.getTenantId(), ticket.getRequesterId(), ticket.getId(),
                "SATISFACTION_REQUESTED", "工单已解决，欢迎评价",
                "工单 " + ticket.getTicketNo() + " 已解决，请对本次服务进行评价。",
                "SATISFACTION_REQUESTED:" + ticket.getId());
    }

    @Transactional
    public Ticket claim(Long id, Long actorId, Long tenantId) {
        Ticket ticket = get(id, tenantId);
        if (ticket.getAssigneeId() != null && !actorId.equals(ticket.getAssigneeId())) {
            throw new BusinessException("工单已由其他处理人领取");
        }
        if (actorId.equals(ticket.getAssigneeId())) return ticket;

        Long beforeAssigneeId = ticket.getAssigneeId();
        ticket.setAssigneeId(actorId);
        ticket.setUpdatedAt(LocalDateTime.now());
        if (ticketMapper.updateById(ticket) == 0) {
            throw new BusinessException("工单已被其他人更新，请刷新后重试");
        }
        recordAssigneeAudit(ticket, actorId, beforeAssigneeId, actorId, "ASSIGNED", "领取工单");
        return ticket;
    }

    @Transactional
    public Ticket release(Long id, Long actorId, boolean admin, Long tenantId) {
        Ticket ticket = get(id, tenantId);
        if (ticket.getAssigneeId() == null) return ticket;
        if (!admin && !actorId.equals(ticket.getAssigneeId())) {
            throw new BusinessException("只有当前处理人或管理员可以释放工单");
        }

        Long beforeAssigneeId = ticket.getAssigneeId();
        ticket.setAssigneeId(null);
        ticket.setUpdatedAt(LocalDateTime.now());
        if (ticketMapper.updateById(ticket) == 0) {
            throw new BusinessException("工单已被其他人更新，请刷新后重试");
        }
        recordAssigneeAudit(ticket, actorId, beforeAssigneeId, null, "UNASSIGNED", "释放工单");
        return ticket;
    }

    @Transactional
    public Ticket assign(Long id, Long assigneeId, Long actorId, Long tenantId) {
        Ticket ticket = get(id, tenantId);
        if (assigneeId.equals(ticket.getAssigneeId())) return ticket;

        Long beforeAssigneeId = ticket.getAssigneeId();
        ticket.setAssigneeId(assigneeId);
        ticket.setUpdatedAt(LocalDateTime.now());
        if (ticketMapper.updateById(ticket) == 0) {
            throw new BusinessException("工单已被其他人更新，请刷新后重试");
        }
        recordAssigneeAudit(ticket, actorId, beforeAssigneeId, assigneeId, "ASSIGNED", "指派工单");
        return ticket;
    }

    private void recordAssigneeAudit(Ticket ticket, Long actorId, Long beforeAssigneeId,
                                     Long afterAssigneeId, String action, String detail) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("assigneeId", beforeAssigneeId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("assigneeId", afterAssigneeId);
        auditLogService.record(ticket.getTenantId(), actorId, "TICKET", ticket.getId(), action,
                before, after, detail);
    }

    public Ticket get(Long id, Long tenantId) {
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getId, id).eq(Ticket::getTenantId, tenantId));
        if (ticket == null) throw new BusinessException("工单不存在: " + id);
        return ticket;
    }

    public Ticket getVisible(Long id, Long tenantId, Long requesterId) {
        Ticket ticket = get(id, tenantId);
        if (requesterId != null && !requesterId.equals(ticket.getRequesterId())) {
            throw new BusinessException("无权访问该工单");
        }
        return ticket;
    }

    private Map<String, Object> ticketSnapshot(Ticket ticket) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", ticket.getTitle());
        snapshot.put("description", ticket.getDescription());
        snapshot.put("category", ticket.getCategory());
        snapshot.put("priority", ticket.getPriority() == null ? null : ticket.getPriority().name());
        return snapshot;
    }

    private String requiredText(String value, String label, int maxLength) {
        if (value == null || value.trim().isBlank()) {
            throw new BusinessException(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private String optionalText(String value, String label, int maxLength) {
        if (value == null || value.trim().isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(label + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
    private boolean isAllowed(TicketStatus from, TicketStatus to) {
        return switch (from) {
            case OPEN -> to == TicketStatus.IN_PROGRESS || to == TicketStatus.REJECTED;
            case IN_PROGRESS -> to == TicketStatus.WAITING_CONFIRMATION || to == TicketStatus.REJECTED;
            case WAITING_CONFIRMATION -> to == TicketStatus.RESOLVED || to == TicketStatus.IN_PROGRESS;
            case RESOLVED -> to == TicketStatus.CLOSED || to == TicketStatus.IN_PROGRESS;
            case REJECTED, CLOSED -> false;
        };
    }

    public record SlaSummary(long active, long overdue, long dueSoon, long onTrack) { }

    public record BatchUpdateResult(int updated, TicketStatus status, TicketPriority priority) { }
}
