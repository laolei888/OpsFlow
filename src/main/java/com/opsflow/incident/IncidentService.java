package com.opsflow.incident;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.StaffSummary;
import com.opsflow.cmdb.CmdbAsset;
import com.opsflow.cmdb.CmdbAssetMapper;
import com.opsflow.common.BusinessException;
import com.opsflow.messaging.AsyncNotificationPublisher;
import com.opsflow.oncall.OnCallEscalationService;
import com.opsflow.postmortem.IncidentPostmortem;
import com.opsflow.postmortem.IncidentPostmortemMapper;
import com.opsflow.postmortem.PostmortemStatus;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
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
public class IncidentService {
    private static final Set<IncidentStatus> TERMINAL_STATUSES = Set.of(IncidentStatus.CLOSED, IncidentStatus.CANCELLED);
    private static final Set<String> TICKET_LINK_TYPES = Set.of("SYMPTOM", "RELATED", "MITIGATION");
    private static final Set<String> ASSET_IMPACT_LEVELS = Set.of("IMPACTED", "DEGRADED", "RELATED");

    private final IncidentMapper incidentMapper;
    private final IncidentUpdateMapper updateMapper;
    private final IncidentTaskMapper taskMapper;
    private final IncidentTicketLinkMapper ticketLinkMapper;
    private final IncidentAssetLinkMapper assetLinkMapper;
    private final IncidentPostmortemMapper postmortemMapper;
    private final TicketMapper ticketMapper;
    private final CmdbAssetMapper assetMapper;
    private final AuthService authService;
    private final AsyncNotificationPublisher notificationPublisher;
    private final OnCallEscalationService onCallEscalationService;
    private final AuditLogService auditLogService;

    public List<Incident> list(Long tenantId, IncidentStatus status, IncidentSeverity severity,
                               String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<Incident> query = new LambdaQueryWrapper<Incident>()
                .eq(Incident::getTenantId, tenantId)
                .orderByAsc(Incident::getSeverity)
                .orderByDesc(Incident::getCreatedAt)
                .orderByDesc(Incident::getId)
                .last("LIMIT " + safeLimit);
        if (status != null) query.eq(Incident::getStatus, status);
        if (severity != null) query.eq(Incident::getSeverity, severity);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(Incident::getIncidentNo, search)
                    .or().like(Incident::getTitle, search)
                    .or().like(Incident::getSummary, search)
                    .or().like(Incident::getBusinessImpact, search));
        }
        return incidentMapper.selectList(query);
    }

    public IncidentDetail detail(Long id, Long tenantId) {
        Incident incident = find(id, tenantId);
        List<IncidentUpdate> updates = updateMapper.selectList(new LambdaQueryWrapper<IncidentUpdate>()
                .eq(IncidentUpdate::getTenantId, tenantId)
                .eq(IncidentUpdate::getIncidentId, id)
                .orderByAsc(IncidentUpdate::getCreatedAt)
                .orderByAsc(IncidentUpdate::getId));
        List<IncidentTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<IncidentTask>()
                .eq(IncidentTask::getTenantId, tenantId)
                .eq(IncidentTask::getIncidentId, id)
                .orderByAsc(IncidentTask::getSortOrder)
                .orderByAsc(IncidentTask::getId));
        List<IncidentTicketLink> ticketLinks = ticketLinkMapper.selectList(new LambdaQueryWrapper<IncidentTicketLink>()
                .eq(IncidentTicketLink::getTenantId, tenantId)
                .eq(IncidentTicketLink::getIncidentId, id)
                .orderByDesc(IncidentTicketLink::getCreatedAt));
        List<IncidentAssetLink> assetLinks = assetLinkMapper.selectList(new LambdaQueryWrapper<IncidentAssetLink>()
                .eq(IncidentAssetLink::getTenantId, tenantId)
                .eq(IncidentAssetLink::getIncidentId, id)
                .orderByDesc(IncidentAssetLink::getCreatedAt));

        Map<Long, Ticket> tickets = tickets(ticketLinks, tenantId);
        Map<Long, CmdbAsset> assets = assets(assetLinks, tenantId);
        List<IncidentTicketView> ticketViews = ticketLinks.stream()
                .filter(link -> tickets.containsKey(link.getTicketId()))
                .map(link -> new IncidentTicketView(link.getTicketId(), link.getLinkType(), link.getNote(),
                        tickets.get(link.getTicketId()), link.getCreatedAt()))
                .toList();
        List<IncidentAssetView> assetViews = assetLinks.stream()
                .filter(link -> assets.containsKey(link.getAssetId()))
                .map(link -> new IncidentAssetView(link.getAssetId(), link.getImpactLevel(), link.getNote(),
                        assets.get(link.getAssetId()), link.getCreatedAt()))
                .toList();
        return new IncidentDetail(incident, updates, tasks, ticketViews, assetViews);
    }

    @Transactional
    public Incident create(IncidentCommand command, String rawIdempotencyKey, Long actorId, Long tenantId) {
        validateCreateCommand(command);
        String requestKey = normalizeKey(rawIdempotencyKey);
        String requestHash = requestHash(command);
        if (requestKey != null) {
            Incident existing = incidentMapper.selectOne(new LambdaQueryWrapper<Incident>()
                    .eq(Incident::getTenantId, tenantId)
                    .eq(Incident::getDeclaredBy, actorId)
                    .eq(Incident::getRequestKey, requestKey));
            if (existing != null) {
                if (!requestHash.equals(existing.getRequestHash())) throw new BusinessException("幂等键已用于其他重大事件");
                return existing;
            }
        }
        Long commanderId = command.commanderId() == null ? actorId : command.commanderId();
        validateStaff(commanderId, tenantId, "事件指挥人");
        LocalDateTime now = LocalDateTime.now();
        Incident incident = new Incident();
        incident.setTenantId(tenantId);
        incident.setIncidentNo("INC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        incident.setTitle(normalize(command.title()));
        incident.setSummary(normalize(command.summary()));
        incident.setSeverity(command.severity());
        incident.setStatus(IncidentStatus.DECLARED);
        incident.setCommanderId(commanderId);
        incident.setDeclaredBy(actorId);
        incident.setBusinessImpact(normalize(command.businessImpact()));
        incident.setCustomerImpact(normalize(command.customerImpact()));
        incident.setCommunicationChannel(normalize(command.communicationChannel()));
        incident.setRequestKey(requestKey);
        incident.setRequestHash(requestKey == null ? null : requestHash);
        incident.setStartedAt(now);
        incident.setCreatedAt(now);
        incident.setUpdatedAt(now);
        try {
            incidentMapper.insert(incident);
        } catch (DataIntegrityViolationException ex) {
            if (requestKey != null) {
                Incident existing = incidentMapper.selectOne(new LambdaQueryWrapper<Incident>()
                        .eq(Incident::getTenantId, tenantId)
                        .eq(Incident::getDeclaredBy, actorId)
                        .eq(Incident::getRequestKey, requestKey));
                if (existing != null && requestHash.equals(existing.getRequestHash())) return existing;
            }
            throw new BusinessException("重大事件宣布失败，请稍后重试");
        }
        createTimeline(incident.getId(), tenantId, actorId, IncidentUpdateType.SYSTEM,
                "重大事件已宣布，事件指挥人：用户 #" + commanderId, now);
        createTasks(incident.getId(), command.taskTitles(), tenantId, actorId, now);
        auditLogService.record(tenantId, actorId, "INCIDENT", incident.getId(), "INCIDENT_DECLARED",
                null, incident, "宣布重大事件");
        onCallEscalationService.triggerIncident(incident);
        notifyDeclaration(incident);
        return incident;
    }

    @Transactional
    public Incident update(Long id, IncidentUpdateCommand command, Long actorId, Long tenantId) {
        Incident incident = lock(id, tenantId);
        ensureEditable(incident);
        Incident before = copy(incident);
        if (command.severity() != null) incident.setSeverity(command.severity());
        if (command.commanderId() != null) {
            validateStaff(command.commanderId(), tenantId, "事件指挥人");
            incident.setCommanderId(command.commanderId());
        }
        if (command.businessImpact() != null) incident.setBusinessImpact(normalize(command.businessImpact()));
        if (command.customerImpact() != null) incident.setCustomerImpact(normalize(command.customerImpact()));
        if (command.communicationChannel() != null) incident.setCommunicationChannel(normalize(command.communicationChannel()));
        if (command.postmortem() != null) incident.setPostmortem(normalize(command.postmortem()));
        incident.setUpdatedAt(LocalDateTime.now());
        incidentMapper.updateById(incident);
        auditLogService.record(tenantId, actorId, "INCIDENT", id, "INCIDENT_UPDATED",
                before, incident, "更新重大事件指挥资料");
        return incident;
    }

    @Transactional
    public Incident transition(Long id, IncidentStatus target, Long actorId, Long tenantId) {
        if (target == null) throw new BusinessException("目标事件状态不能为空");
        Incident incident = lock(id, tenantId);
        IncidentStatus before = incident.getStatus();
        validateTransition(before, target);
        if (target == IncidentStatus.CLOSED) {
            boolean legacyPostmortemComplete = incident.getPostmortem() != null && !incident.getPostmortem().isBlank();
            boolean structuredPostmortemApproved = postmortemMapper.selectCount(
                    new LambdaQueryWrapper<IncidentPostmortem>()
                            .eq(IncidentPostmortem::getTenantId, tenantId)
                            .eq(IncidentPostmortem::getIncidentId, id)
                            .in(IncidentPostmortem::getStatus, PostmortemStatus.APPROVED, PostmortemStatus.PUBLISHED)) > 0;
            if (!legacyPostmortemComplete && !structuredPostmortemApproved) {
                throw new BusinessException("关闭重大事件前必须填写事后复盘，或完成结构化复盘审批");
            }
            if (taskMapper.selectCount(new LambdaQueryWrapper<IncidentTask>()
                    .eq(IncidentTask::getTenantId, tenantId)
                    .eq(IncidentTask::getIncidentId, id)
                    .ne(IncidentTask::getStatus, IncidentTaskStatus.DONE)) > 0) {
                throw new BusinessException("仍有未完成的事件协同任务，不能关闭重大事件");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        incident.setStatus(target);
        if (target == IncidentStatus.RESOLVED) incident.setResolvedAt(now);
        if (target == IncidentStatus.CLOSED) incident.setClosedAt(now);
        if (target == IncidentStatus.INVESTIGATING || target == IncidentStatus.MITIGATING) {
            incident.setResolvedAt(null);
            incident.setClosedAt(null);
        }
        incident.setUpdatedAt(now);
        if (incidentMapper.updateById(incident) == 0) throw new BusinessException("事件状态更新失败，请刷新后重试");
        createTimeline(id, tenantId, actorId, IncidentUpdateType.SYSTEM,
                "事件状态从「" + before + "」流转至「" + target + "」", now);
        auditLogService.record(tenantId, actorId, "INCIDENT", id, "INCIDENT_STATUS_CHANGED",
                Map.of("status", before.name()), Map.of("status", target.name()), "重大事件状态流转");
        if (target == IncidentStatus.RESOLVED || target == IncidentStatus.CLOSED) {
            onCallEscalationService.resolveIncident(incident, actorId, false);
        } else if (target == IncidentStatus.CANCELLED) {
            onCallEscalationService.resolveIncident(incident, actorId, true);
        }
        notifyCommander(incident, actorId, target);
        return incident;
    }

    @Transactional
    public IncidentUpdate addUpdate(Long incidentId, UpdateCommand command, Long actorId, Long tenantId) {
        Incident incident = find(incidentId, tenantId);
        ensureEditable(incident);
        String content = normalize(command.content());
        if (content == null || content.length() > 10000) throw new BusinessException("事件动态不能为空且不能超过 10000 个字符");
        IncidentUpdate update = createTimeline(incidentId, tenantId, actorId,
                command.updateType() == null ? IncidentUpdateType.INTERNAL_NOTE : command.updateType(),
                content, LocalDateTime.now());
        auditLogService.record(tenantId, actorId, "INCIDENT", incidentId, "INCIDENT_UPDATE_ADDED",
                null, update, "发布重大事件动态");
        return update;
    }

    @Transactional
    public IncidentTask createTask(Long incidentId, TaskCommand command, Long actorId, Long tenantId) {
        Incident incident = find(incidentId, tenantId);
        ensureEditable(incident);
        String title = normalize(command.title());
        if (title == null || title.length() > 160) throw new BusinessException("协同任务名称不能为空且不能超过 160 个字符");
        if (command.assigneeId() != null) validateStaff(command.assigneeId(), tenantId, "任务负责人");
        LocalDateTime now = LocalDateTime.now();
        IncidentTask task = new IncidentTask();
        task.setTenantId(tenantId);
        task.setIncidentId(incidentId);
        task.setTitle(title);
        task.setAssigneeId(command.assigneeId());
        task.setSortOrder(command.sortOrder() == null ? 100 : Math.max(0, Math.min(command.sortOrder(), 9999)));
        task.setStatus(IncidentTaskStatus.TODO);
        task.setCreatedBy(actorId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        auditLogService.record(tenantId, actorId, "INCIDENT", incidentId, "INCIDENT_TASK_CREATED",
                null, task, "新增事件协同任务");
        return task;
    }

    @Transactional
    public IncidentTask updateTask(Long taskId, TaskUpdateCommand command, Long actorId, Long tenantId) {
        IncidentTask task = taskMapper.selectOne(new LambdaQueryWrapper<IncidentTask>()
                .eq(IncidentTask::getId, taskId)
                .eq(IncidentTask::getTenantId, tenantId));
        if (task == null) throw new BusinessException("事件协同任务不存在");
        Incident incident = find(task.getIncidentId(), tenantId);
        ensureEditable(incident);
        IncidentTask before = copy(task);
        if (command.title() != null) {
            String title = normalize(command.title());
            if (title == null || title.length() > 160) throw new BusinessException("协同任务名称不能为空且不能超过 160 个字符");
            task.setTitle(title);
        }
        if (command.assigneeId() != null) {
            validateStaff(command.assigneeId(), tenantId, "任务负责人");
            task.setAssigneeId(command.assigneeId());
        }
        if (command.status() != null) {
            task.setStatus(command.status());
            task.setCompletedAt(command.status() == IncidentTaskStatus.DONE ? LocalDateTime.now() : null);
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        auditLogService.record(tenantId, actorId, "INCIDENT", incident.getId(), "INCIDENT_TASK_UPDATED",
                before, task, "更新事件协同任务");
        return task;
    }

    @Transactional
    public IncidentTicketView linkTicket(Long incidentId, LinkCommand command, Long actorId, Long tenantId) {
        Incident incident = find(incidentId, tenantId);
        ensureEditable(incident);
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getId, command.targetId())
                .eq(Ticket::getTenantId, tenantId));
        if (ticket == null) throw new BusinessException("关联的工单不存在");
        String linkType = normalizeType(command.linkType(), TICKET_LINK_TYPES, "工单关联类型", "SYMPTOM");
        IncidentTicketLink existing = ticketLinkMapper.selectOne(new LambdaQueryWrapper<IncidentTicketLink>()
                .eq(IncidentTicketLink::getTenantId, tenantId)
                .eq(IncidentTicketLink::getIncidentId, incidentId)
                .eq(IncidentTicketLink::getTicketId, ticket.getId()));
        if (existing != null) return new IncidentTicketView(ticket.getId(), existing.getLinkType(), existing.getNote(),
                ticket, existing.getCreatedAt());
        IncidentTicketLink link = new IncidentTicketLink();
        link.setTenantId(tenantId);
        link.setIncidentId(incidentId);
        link.setTicketId(ticket.getId());
        link.setLinkType(linkType);
        link.setNote(normalize(command.note()));
        link.setCreatedBy(actorId);
        link.setCreatedAt(LocalDateTime.now());
        ticketLinkMapper.insert(link);
        IncidentTicketView view = new IncidentTicketView(ticket.getId(), link.getLinkType(), link.getNote(), ticket,
                link.getCreatedAt());
        auditLogService.record(tenantId, actorId, "INCIDENT", incidentId, "INCIDENT_TICKET_LINKED",
                null, view, "关联事件工单");
        return view;
    }

    @Transactional
    public void unlinkTicket(Long incidentId, Long ticketId, Long actorId, Long tenantId) {
        Incident incident = find(incidentId, tenantId);
        ensureEditable(incident);
        IncidentTicketLink link = ticketLinkMapper.selectOne(new LambdaQueryWrapper<IncidentTicketLink>()
                .eq(IncidentTicketLink::getTenantId, tenantId)
                .eq(IncidentTicketLink::getIncidentId, incidentId)
                .eq(IncidentTicketLink::getTicketId, ticketId));
        if (link == null) throw new BusinessException("重大事件尚未关联该工单");
        ticketLinkMapper.delete(new LambdaQueryWrapper<IncidentTicketLink>()
                .eq(IncidentTicketLink::getTenantId, tenantId)
                .eq(IncidentTicketLink::getIncidentId, incidentId)
                .eq(IncidentTicketLink::getTicketId, ticketId));
        auditLogService.record(tenantId, actorId, "INCIDENT", incidentId, "INCIDENT_TICKET_UNLINKED",
                Map.of("ticketId", ticketId), null, "解除事件工单关联");
    }

    @Transactional
    public IncidentAssetView linkAsset(Long incidentId, LinkCommand command, Long actorId, Long tenantId) {
        Incident incident = find(incidentId, tenantId);
        ensureEditable(incident);
        CmdbAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<CmdbAsset>()
                .eq(CmdbAsset::getId, command.targetId())
                .eq(CmdbAsset::getTenantId, tenantId));
        if (asset == null) throw new BusinessException("关联的配置项不存在");
        String impactLevel = normalizeType(command.linkType(), ASSET_IMPACT_LEVELS, "资产影响级别", "IMPACTED");
        IncidentAssetLink existing = assetLinkMapper.selectOne(new LambdaQueryWrapper<IncidentAssetLink>()
                .eq(IncidentAssetLink::getTenantId, tenantId)
                .eq(IncidentAssetLink::getIncidentId, incidentId)
                .eq(IncidentAssetLink::getAssetId, asset.getId()));
        if (existing != null) return new IncidentAssetView(asset.getId(), existing.getImpactLevel(), existing.getNote(),
                asset, existing.getCreatedAt());
        IncidentAssetLink link = new IncidentAssetLink();
        link.setTenantId(tenantId);
        link.setIncidentId(incidentId);
        link.setAssetId(asset.getId());
        link.setImpactLevel(impactLevel);
        link.setNote(normalize(command.note()));
        link.setCreatedBy(actorId);
        link.setCreatedAt(LocalDateTime.now());
        assetLinkMapper.insert(link);
        IncidentAssetView view = new IncidentAssetView(asset.getId(), link.getImpactLevel(), link.getNote(), asset,
                link.getCreatedAt());
        auditLogService.record(tenantId, actorId, "INCIDENT", incidentId, "INCIDENT_ASSET_LINKED",
                null, view, "关联受影响配置项");
        return view;
    }

    @Transactional
    public void unlinkAsset(Long incidentId, Long assetId, Long actorId, Long tenantId) {
        Incident incident = find(incidentId, tenantId);
        ensureEditable(incident);
        IncidentAssetLink link = assetLinkMapper.selectOne(new LambdaQueryWrapper<IncidentAssetLink>()
                .eq(IncidentAssetLink::getTenantId, tenantId)
                .eq(IncidentAssetLink::getIncidentId, incidentId)
                .eq(IncidentAssetLink::getAssetId, assetId));
        if (link == null) throw new BusinessException("重大事件尚未关联该配置项");
        assetLinkMapper.delete(new LambdaQueryWrapper<IncidentAssetLink>()
                .eq(IncidentAssetLink::getTenantId, tenantId)
                .eq(IncidentAssetLink::getIncidentId, incidentId)
                .eq(IncidentAssetLink::getAssetId, assetId));
        auditLogService.record(tenantId, actorId, "INCIDENT", incidentId, "INCIDENT_ASSET_UNLINKED",
                Map.of("assetId", assetId), null, "解除事件配置项关联");
    }

    private Map<Long, Ticket> tickets(List<IncidentTicketLink> links, Long tenantId) {
        if (links.isEmpty()) return Map.of();
        Set<Long> ids = new HashSet<>();
        links.forEach(link -> ids.add(link.getTicketId()));
        Map<Long, Ticket> result = new HashMap<>();
        ticketMapper.selectBatchIds(ids).forEach(ticket -> {
            if (tenantId.equals(ticket.getTenantId())) result.put(ticket.getId(), ticket);
        });
        return result;
    }

    private Map<Long, CmdbAsset> assets(List<IncidentAssetLink> links, Long tenantId) {
        if (links.isEmpty()) return Map.of();
        Set<Long> ids = new HashSet<>();
        links.forEach(link -> ids.add(link.getAssetId()));
        Map<Long, CmdbAsset> result = new HashMap<>();
        assetMapper.selectBatchIds(ids).forEach(asset -> {
            if (tenantId.equals(asset.getTenantId())) result.put(asset.getId(), asset);
        });
        return result;
    }

    private void validateCreateCommand(IncidentCommand command) {
        if (command == null) throw new BusinessException("重大事件资料不能为空");
        String title = normalize(command.title());
        String summary = normalize(command.summary());
        if (title == null || title.length() > 160) throw new BusinessException("事件标题不能为空且不能超过 160 个字符");
        if (summary == null || summary.length() > 10000) throw new BusinessException("事件摘要不能为空且不能超过 10000 个字符");
        if (command.severity() == null) throw new BusinessException("请选择事件严重等级");
        validateLength(command.businessImpact(), 1000, "业务影响");
        validateLength(command.customerImpact(), 1000, "客户影响");
        validateLength(command.communicationChannel(), 200, "沟通渠道");
        if (command.taskTitles() != null && command.taskTitles().size() > 30) {
            throw new BusinessException("单个重大事件最多 30 个协同任务");
        }
    }

    private void validateLength(String value, int max, String field) {
        if (value != null && value.trim().length() > max) throw new BusinessException(field + "不能超过 " + max + " 个字符");
    }

    private void validateStaff(Long userId, Long tenantId, String field) {
        if (userId == null || !authService.isStaffMember(userId, tenantId)) {
            throw new BusinessException(field + "不存在或不是当前租户的客服账号");
        }
    }

    private void validateTransition(IncidentStatus before, IncidentStatus target) {
        boolean allowed = switch (before) {
            case DECLARED -> target == IncidentStatus.INVESTIGATING || target == IncidentStatus.CANCELLED;
            case INVESTIGATING -> target == IncidentStatus.MITIGATING || target == IncidentStatus.CANCELLED;
            case MITIGATING -> target == IncidentStatus.MONITORING || target == IncidentStatus.INVESTIGATING
                    || target == IncidentStatus.CANCELLED;
            case MONITORING -> target == IncidentStatus.RESOLVED || target == IncidentStatus.MITIGATING;
            case RESOLVED -> target == IncidentStatus.CLOSED || target == IncidentStatus.INVESTIGATING;
            case CLOSED, CANCELLED -> false;
        };
        if (!allowed) throw new BusinessException("不允许从「" + before + "」流转到「" + target + "」");
    }

    private void ensureEditable(Incident incident) {
        if (TERMINAL_STATUSES.contains(incident.getStatus())) throw new BusinessException("当前重大事件状态不允许修改");
    }

    private Incident find(Long id, Long tenantId) {
        Incident incident = incidentMapper.selectOne(new LambdaQueryWrapper<Incident>()
                .eq(Incident::getId, id)
                .eq(Incident::getTenantId, tenantId));
        if (incident == null) throw new BusinessException("重大事件不存在");
        return incident;
    }

    private Incident lock(Long id, Long tenantId) {
        Incident incident = incidentMapper.selectForUpdate(id, tenantId);
        if (incident == null) throw new BusinessException("重大事件不存在");
        return incident;
    }

    private IncidentUpdate createTimeline(Long incidentId, Long tenantId, Long actorId,
                                          IncidentUpdateType type, String content, LocalDateTime now) {
        IncidentUpdate update = new IncidentUpdate();
        update.setTenantId(tenantId);
        update.setIncidentId(incidentId);
        update.setUpdateType(type);
        update.setContent(content);
        update.setCreatedBy(actorId);
        update.setCreatedAt(now);
        updateMapper.insert(update);
        return update;
    }

    private void createTasks(Long incidentId, List<String> titles, Long tenantId, Long actorId, LocalDateTime now) {
        if (titles == null) return;
        int order = 10;
        for (String raw : titles) {
            String title = normalize(raw);
            if (title == null) continue;
            if (title.length() > 160) throw new BusinessException("协同任务名称不能超过 160 个字符");
            IncidentTask task = new IncidentTask();
            task.setTenantId(tenantId);
            task.setIncidentId(incidentId);
            task.setTitle(title);
            task.setSortOrder(order);
            task.setStatus(IncidentTaskStatus.TODO);
            task.setCreatedBy(actorId);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            taskMapper.insert(task);
            order += 10;
        }
    }

    private void notifyDeclaration(Incident incident) {
        if (incident.getSeverity() != IncidentSeverity.SEV1 && incident.getSeverity() != IncidentSeverity.SEV2) return;
        for (StaffSummary member : authService.staffMembers(incident.getTenantId())) {
            notificationPublisher.enqueue(incident.getTenantId(), member.id(), null,
                    "INCIDENT_DECLARED", "重大事件已宣布",
                    "「" + incident.getTitle() + "」当前等级：" + incident.getSeverity(),
                    "INCIDENT_DECLARED:" + incident.getId() + ":" + member.id(),
                    "INCIDENT", incident.getId());
        }
    }

    private void notifyCommander(Incident incident, Long actorId, IncidentStatus target) {
        if (incident.getCommanderId() == null || incident.getCommanderId().equals(actorId)) return;
        notificationPublisher.enqueue(incident.getTenantId(), incident.getCommanderId(), null,
                "INCIDENT_STATUS_CHANGED", "重大事件状态已更新",
                "「" + incident.getTitle() + "」当前状态：" + target,
                "INCIDENT_STATUS_CHANGED:" + incident.getId() + ":" + target,
                "INCIDENT", incident.getId());
    }

    private String normalizeType(String raw, Set<String> allowed, String field, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) throw new BusinessException("无效的" + field);
        return value;
    }

    private String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > 128) throw new BusinessException("Idempotency-Key 不能超过 128 个字符");
        return value;
    }

    private String requestHash(IncidentCommand command) {
        String source = String.join("\u0000", value(command.title()), value(command.summary()),
                command.severity() == null ? "" : command.severity().name(), value(command.businessImpact()),
                value(command.customerImpact()), value(command.communicationChannel()),
                command.commanderId() == null ? "" : String.valueOf(command.commanderId()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private Incident copy(Incident source) {
        Incident result = new Incident();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setIncidentNo(source.getIncidentNo());
        result.setTitle(source.getTitle());
        result.setSummary(source.getSummary());
        result.setSeverity(source.getSeverity());
        result.setStatus(source.getStatus());
        result.setCommanderId(source.getCommanderId());
        result.setDeclaredBy(source.getDeclaredBy());
        result.setBusinessImpact(source.getBusinessImpact());
        result.setCustomerImpact(source.getCustomerImpact());
        result.setCommunicationChannel(source.getCommunicationChannel());
        result.setPostmortem(source.getPostmortem());
        result.setRequestKey(source.getRequestKey());
        result.setRequestHash(source.getRequestHash());
        result.setStartedAt(source.getStartedAt());
        result.setResolvedAt(source.getResolvedAt());
        result.setClosedAt(source.getClosedAt());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private IncidentTask copy(IncidentTask source) {
        IncidentTask result = new IncidentTask();
        result.setId(source.getId());
        result.setTenantId(source.getTenantId());
        result.setIncidentId(source.getIncidentId());
        result.setTitle(source.getTitle());
        result.setAssigneeId(source.getAssigneeId());
        result.setSortOrder(source.getSortOrder());
        result.setStatus(source.getStatus());
        result.setCompletedAt(source.getCompletedAt());
        result.setCreatedBy(source.getCreatedBy());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private String value(String source) { return source == null ? "" : source.trim(); }

    private String normalize(String source) {
        if (source == null) return null;
        String result = source.trim();
        return result.isBlank() ? null : result;
    }

    public record IncidentCommand(String title, String summary, IncidentSeverity severity, Long commanderId,
                                  String businessImpact, String customerImpact, String communicationChannel,
                                  List<String> taskTitles) { }

    public record IncidentUpdateCommand(IncidentSeverity severity, Long commanderId, String businessImpact,
                                        String customerImpact, String communicationChannel, String postmortem) { }

    public record UpdateCommand(IncidentUpdateType updateType, String content) { }

    public record TaskCommand(String title, Long assigneeId, Integer sortOrder) { }

    public record TaskUpdateCommand(String title, Long assigneeId, IncidentTaskStatus status) { }

    public record LinkCommand(Long targetId, String linkType, String note) { }

    public record IncidentDetail(Incident incident, List<IncidentUpdate> updates, List<IncidentTask> tasks,
                                 List<IncidentTicketView> tickets, List<IncidentAssetView> assets) { }

    public record IncidentTicketView(Long ticketId, String linkType, String note,
                                    Ticket ticket, LocalDateTime createdAt) { }

    public record IncidentAssetView(Long assetId, String impactLevel, String note,
                                   CmdbAsset asset, LocalDateTime createdAt) { }
}
