package com.opsflow.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.common.BusinessException;
import com.opsflow.messaging.AsyncNotificationPublisher;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceCatalogService {
    private final ServiceCatalogItemMapper itemMapper;
    private final ServiceRequestMapper requestMapper;
    private final TicketService ticketService;
    private final AsyncNotificationPublisher notificationPublisher;
    private final AuditLogService auditLogService;
    private final AuthService authService;

    @Transactional(readOnly = true)
    public List<ServiceCatalogItem> listItems(Long tenantId, boolean includeDisabled) {
        LambdaQueryWrapper<ServiceCatalogItem> query = new LambdaQueryWrapper<ServiceCatalogItem>()
                .eq(ServiceCatalogItem::getTenantId, tenantId)
                .orderByAsc(ServiceCatalogItem::getSortOrder)
                .orderByAsc(ServiceCatalogItem::getName);
        if (!includeDisabled) query.eq(ServiceCatalogItem::getEnabled, true);
        return itemMapper.selectList(query);
    }

    @Transactional
    public ServiceCatalogItem createItem(CatalogItemCommand command, Long actorId, Long tenantId) {
        CatalogItemCommand safe = validate(command);
        ensureUniqueName(safe.name(), tenantId, null);
        LocalDateTime now = LocalDateTime.now();
        ServiceCatalogItem item = new ServiceCatalogItem();
        item.setTenantId(tenantId);
        apply(item, safe);
        item.setCreatedBy(actorId);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        itemMapper.insert(item);
        auditLogService.record(tenantId, actorId, "SERVICE_CATALOG_ITEM", item.getId(),
                "SERVICE_CATALOG_ITEM_CREATED", null, item, "创建服务目录项");
        return item;
    }

    @Transactional
    public ServiceCatalogItem updateItem(Long id, CatalogItemCommand command, Long actorId, Long tenantId) {
        ServiceCatalogItem item = findItem(id, tenantId, false);
        CatalogItemCommand safe = validate(command);
        ensureUniqueName(safe.name(), tenantId, id);
        ServiceCatalogItem before = copyItem(item);
        apply(item, safe);
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);
        auditLogService.record(tenantId, actorId, "SERVICE_CATALOG_ITEM", id,
                "SERVICE_CATALOG_ITEM_UPDATED", before, item, "更新服务目录项");
        return item;
    }

    @Transactional
    public ServiceCatalogItem toggleItem(Long id, Long actorId, Long tenantId) {
        ServiceCatalogItem item = findItem(id, tenantId, false);
        ServiceCatalogItem before = copyItem(item);
        item.setEnabled(!Boolean.TRUE.equals(item.getEnabled()));
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);
        auditLogService.record(tenantId, actorId, "SERVICE_CATALOG_ITEM", id,
                "SERVICE_CATALOG_ITEM_TOGGLED", before, item,
                Boolean.TRUE.equals(item.getEnabled()) ? "启用服务目录项" : "停用服务目录项");
        return item;
    }

    @Transactional(readOnly = true)
    public List<ServiceRequest> listRequests(Long tenantId, Long requesterId, ServiceRequestStatus status) {
        LambdaQueryWrapper<ServiceRequest> query = new LambdaQueryWrapper<ServiceRequest>()
                .eq(ServiceRequest::getTenantId, tenantId)
                .orderByDesc(ServiceRequest::getCreatedAt)
                .orderByDesc(ServiceRequest::getId)
                .last("LIMIT 200");
        if (requesterId != null) query.eq(ServiceRequest::getRequesterId, requesterId);
        if (status != null) query.eq(ServiceRequest::getStatus, status);
        return requestMapper.selectList(query);
    }

    @Transactional
    public ServiceRequest createRequest(Long catalogItemId, String summary, String detail,
                                        String rawRequestKey, Long requesterId, Long tenantId) {
        String safeSummary = requireText(summary, 160, "申请摘要");
        String safeDetail = requireText(detail, 10000, "申请说明");
        String requestKey = normalizeRequestKey(rawRequestKey);
        String requestHash = requestHash(catalogItemId, safeSummary, safeDetail);
        if (requestKey != null) {
            ServiceRequest existing = findIdempotentRequest(requestKey, requestHash, requesterId, tenantId);
            if (existing != null) return existing;
        }

        ServiceCatalogItem item = findItem(catalogItemId, tenantId, true);
        LocalDateTime now = LocalDateTime.now();
        ServiceRequest request = new ServiceRequest();
        request.setRequestNo("SR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        request.setTenantId(tenantId);
        request.setCatalogItemId(item.getId());
        request.setRequesterId(requesterId);
        request.setRequestKey(requestKey);
        request.setRequestHash(requestHash);
        request.setItemName(item.getName());
        request.setCategory(item.getCategory());
        request.setPriority(item.getDefaultPriority());
        request.setSummary(safeSummary);
        request.setDetail(safeDetail);
        request.setStatus(Boolean.TRUE.equals(item.getApprovalRequired())
                ? ServiceRequestStatus.PENDING_APPROVAL : ServiceRequestStatus.APPROVED);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (!Boolean.TRUE.equals(item.getApprovalRequired())) {
            request.setDecisionComment("无需审批，系统自动受理");
            request.setDecidedAt(now);
        }
        try {
            requestMapper.insert(request);
        } catch (DataIntegrityViolationException ex) {
            ServiceRequest existing = findIdempotentRequest(requestKey, requestHash, requesterId, tenantId);
            if (existing != null) return existing;
            throw new BusinessException("相同服务申请正在处理中，请稍后重试");
        }

        if (request.getStatus() == ServiceRequestStatus.PENDING_APPROVAL) {
            auditLogService.record(tenantId, requesterId, "SERVICE_REQUEST", request.getId(),
                    "SERVICE_REQUEST_CREATED", null, request, "提交服务申请，等待管理员审批");
            notifyApprovers(request);
        } else {
            Ticket ticket = createTicket(request);
            request.setTicketId(ticket.getId());
            request.setUpdatedAt(LocalDateTime.now());
            requestMapper.updateById(request);
            auditLogService.record(tenantId, requesterId, "SERVICE_REQUEST", request.getId(),
                    "SERVICE_REQUEST_AUTO_APPROVED", null, request, "服务申请无需审批，已自动创建工单");
            notifyRequester(request, "服务申请已受理",
                    request.getRequestNo() + " 已自动通过，并创建工单 " + ticket.getTicketNo() + "。",
                    "SERVICE_REQUEST_AUTO_APPROVED");
        }
        return request;
    }

    @Transactional
    public ServiceRequest approve(Long id, String comment, Long actorId, Long tenantId) {
        ServiceRequest request = findPendingForUpdate(id, tenantId);
        ServiceRequest before = copyRequest(request);
        Ticket ticket = createTicket(request);
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(ServiceRequestStatus.APPROVED);
        request.setTicketId(ticket.getId());
        request.setDecisionComment(normalizeComment(comment, "审批通过"));
        request.setDecidedBy(actorId);
        request.setDecidedAt(now);
        request.setUpdatedAt(now);
        requestMapper.updateById(request);
        auditLogService.record(tenantId, actorId, "SERVICE_REQUEST", id,
                "SERVICE_REQUEST_APPROVED", before, request, "审批通过并创建工单 " + ticket.getTicketNo());
        notifyRequester(request, "服务申请已通过",
                request.getRequestNo() + " 已审批通过，并创建工单 " + ticket.getTicketNo() + "。",
                "SERVICE_REQUEST_APPROVED");
        return request;
    }

    @Transactional
    public ServiceRequest reject(Long id, String comment, Long actorId, Long tenantId) {
        String reason = requireText(comment, 500, "驳回原因");
        ServiceRequest request = findPendingForUpdate(id, tenantId);
        ServiceRequest before = copyRequest(request);
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(ServiceRequestStatus.REJECTED);
        request.setDecisionComment(reason);
        request.setDecidedBy(actorId);
        request.setDecidedAt(now);
        request.setUpdatedAt(now);
        requestMapper.updateById(request);
        auditLogService.record(tenantId, actorId, "SERVICE_REQUEST", id,
                "SERVICE_REQUEST_REJECTED", before, request, "驳回服务申请：" + reason);
        notifyRequester(request, "服务申请未通过",
                request.getRequestNo() + " 未通过审批，原因：" + reason,
                "SERVICE_REQUEST_REJECTED");
        return request;
    }

    private Ticket createTicket(ServiceRequest request) {
        Ticket ticket = new Ticket();
        String title = request.getItemName() + " · " + request.getSummary();
        ticket.setTitle(title.length() <= 160 ? title : title.substring(0, 160));
        ticket.setDescription("服务申请 " + request.getRequestNo() + "\n\n" + request.getDetail());
        ticket.setCategory(request.getCategory());
        ticket.setPriority(request.getPriority());
        return ticketService.create(ticket, request.getRequesterId(), request.getTenantId());
    }

    private void notifyApprovers(ServiceRequest request) {
        List<StaffSummary> admins = authService.staffMembers(request.getTenantId()).stream()
                .filter(staff -> staff.roles().contains("ADMIN"))
                .toList();
        for (StaffSummary admin : admins) {
            notificationPublisher.enqueue(request.getTenantId(), admin.id(), null,
                    "SERVICE_APPROVAL_REQUIRED", "待审批服务申请 · " + request.getItemName(),
                    request.getRequestNo() + "：" + request.getSummary(),
                    "SERVICE_APPROVAL_REQUIRED:" + request.getId() + ":" + admin.id(),
                    "SERVICE_REQUEST", request.getId());
        }
    }

    private void notifyRequester(ServiceRequest request, String title, String content, String event) {
        notificationPublisher.enqueue(request.getTenantId(), request.getRequesterId(), null,
                "SERVICE_REQUEST_RESULT", title, content, event + ":" + request.getId(),
                "SERVICE_REQUEST", request.getId());
    }

    private ServiceCatalogItem findItem(Long id, Long tenantId, boolean requireEnabled) {
        LambdaQueryWrapper<ServiceCatalogItem> query = new LambdaQueryWrapper<ServiceCatalogItem>()
                .eq(ServiceCatalogItem::getId, id)
                .eq(ServiceCatalogItem::getTenantId, tenantId);
        if (requireEnabled) query.eq(ServiceCatalogItem::getEnabled, true);
        ServiceCatalogItem item = itemMapper.selectOne(query);
        if (item == null) throw new BusinessException(requireEnabled ? "服务目录项不存在或已停用" : "服务目录项不存在");
        return item;
    }

    private ServiceRequest findPendingForUpdate(Long id, Long tenantId) {
        ServiceRequest request = requestMapper.selectForUpdate(id, tenantId);
        if (request == null) throw new BusinessException("服务申请不存在");
        if (request.getStatus() != ServiceRequestStatus.PENDING_APPROVAL) {
            throw new BusinessException("服务申请已完成审批，不能重复处理");
        }
        return request;
    }

    private ServiceRequest findIdempotentRequest(String key, String hash, Long requesterId, Long tenantId) {
        if (key == null) return null;
        ServiceRequest existing = requestMapper.selectOne(new LambdaQueryWrapper<ServiceRequest>()
                .eq(ServiceRequest::getTenantId, tenantId)
                .eq(ServiceRequest::getRequesterId, requesterId)
                .eq(ServiceRequest::getRequestKey, key));
        if (existing == null) return null;
        if (!Objects.equals(existing.getRequestHash(), hash)) {
            throw new BusinessException("幂等键已用于其他服务申请");
        }
        return existing;
    }

    private void ensureUniqueName(String name, Long tenantId, Long excludedId) {
        LambdaQueryWrapper<ServiceCatalogItem> query = new LambdaQueryWrapper<ServiceCatalogItem>()
                .eq(ServiceCatalogItem::getTenantId, tenantId)
                .eq(ServiceCatalogItem::getName, name);
        if (excludedId != null) query.ne(ServiceCatalogItem::getId, excludedId);
        if (itemMapper.selectCount(query) > 0) throw new BusinessException("同名服务目录项已存在");
    }

    private CatalogItemCommand validate(CatalogItemCommand command) {
        if (command == null) throw new BusinessException("服务目录配置不能为空");
        String name = requireText(command.name(), 100, "服务名称");
        String description = requireText(command.description(), 2000, "服务说明");
        String category = requireText(command.category(), 64, "服务分类");
        TicketPriority priority = command.defaultPriority() == null ? TicketPriority.MEDIUM : command.defaultPriority();
        boolean approvalRequired = Boolean.TRUE.equals(command.approvalRequired());
        boolean enabled = command.enabled() == null || Boolean.TRUE.equals(command.enabled());
        int sortOrder = command.sortOrder() == null ? 100 : command.sortOrder();
        if (sortOrder < 0 || sortOrder > 9999) throw new BusinessException("排序值必须在 0-9999 之间");
        return new CatalogItemCommand(name, description, category, priority,
                approvalRequired, enabled, sortOrder);
    }

    private void apply(ServiceCatalogItem item, CatalogItemCommand command) {
        item.setName(command.name());
        item.setDescription(command.description());
        item.setCategory(command.category());
        item.setDefaultPriority(command.defaultPriority());
        item.setApprovalRequired(command.approvalRequired());
        item.setEnabled(command.enabled());
        item.setSortOrder(command.sortOrder());
    }

    private String normalizeRequestKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        String key = rawKey.trim();
        if (key.length() > 128) throw new BusinessException("Idempotency-Key 不能超过 128 个字符");
        return key;
    }

    private String requestHash(Long itemId, String summary, String detail) {
        String source = itemId + "\u0000" + summary + "\u0000" + detail;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) throw new BusinessException(field + "不能为空");
        String safe = value.trim();
        if (safe.length() > maxLength) throw new BusinessException(field + "不能超过 " + maxLength + " 个字符");
        return safe;
    }

    private String normalizeComment(String comment, String fallback) {
        if (comment == null || comment.isBlank()) return fallback;
        String safe = comment.trim();
        if (safe.length() > 500) throw new BusinessException("审批意见不能超过 500 个字符");
        return safe;
    }

    private ServiceCatalogItem copyItem(ServiceCatalogItem source) {
        ServiceCatalogItem copy = new ServiceCatalogItem();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setCategory(source.getCategory());
        copy.setDefaultPriority(source.getDefaultPriority());
        copy.setApprovalRequired(source.getApprovalRequired());
        copy.setEnabled(source.getEnabled());
        copy.setSortOrder(source.getSortOrder());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private ServiceRequest copyRequest(ServiceRequest source) {
        ServiceRequest copy = new ServiceRequest();
        copy.setId(source.getId());
        copy.setRequestNo(source.getRequestNo());
        copy.setTenantId(source.getTenantId());
        copy.setCatalogItemId(source.getCatalogItemId());
        copy.setRequesterId(source.getRequesterId());
        copy.setTicketId(source.getTicketId());
        copy.setItemName(source.getItemName());
        copy.setCategory(source.getCategory());
        copy.setPriority(source.getPriority());
        copy.setSummary(source.getSummary());
        copy.setDetail(source.getDetail());
        copy.setStatus(source.getStatus());
        copy.setDecisionComment(source.getDecisionComment());
        copy.setDecidedBy(source.getDecidedBy());
        copy.setDecidedAt(source.getDecidedAt());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    public record CatalogItemCommand(String name, String description, String category,
                                     TicketPriority defaultPriority, Boolean approvalRequired,
                                     Boolean enabled, Integer sortOrder) { }
}
