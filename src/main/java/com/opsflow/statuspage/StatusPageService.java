package com.opsflow.statuspage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.cmdb.CmdbAssetService;
import com.opsflow.common.BusinessException;
import com.opsflow.incident.Incident;
import com.opsflow.incident.IncidentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The public feed is deliberately built from separate, editor-approved copies of operational events.
 * No alert or internal incident is published implicitly, even when linked for traceability.
 */
@Service
@RequiredArgsConstructor
public class StatusPageService {
    private final StatusPageComponentMapper componentMapper;
    private final StatusPageIncidentMapper incidentMapper;
    private final StatusPageIncidentComponentMapper incidentComponentMapper;
    private final StatusPageUpdateMapper updateMapper;
    private final IncidentMapper internalIncidentMapper;
    private final CmdbAssetService cmdbAssetService;
    private final AuditLogService auditLogService;

    public StatusCenterView center(Long tenantId) {
        List<StatusPageComponent> components = listComponents(tenantId);
        List<StatusPageIncident> incidents = incidentMapper.selectList(new LambdaQueryWrapper<StatusPageIncident>()
                .eq(StatusPageIncident::getTenantId, tenantId)
                .orderByDesc(StatusPageIncident::getUpdatedAt)
                .orderByDesc(StatusPageIncident::getId)
                .last("LIMIT 100"));
        return new StatusCenterView(components, incidentDetails(incidents, components, tenantId));
    }

    public List<StatusPageComponent> listComponents(Long tenantId) {
        return componentMapper.selectList(new LambdaQueryWrapper<StatusPageComponent>()
                .eq(StatusPageComponent::getTenantId, tenantId)
                .orderByAsc(StatusPageComponent::getDisplayOrder)
                .orderByAsc(StatusPageComponent::getName));
    }

    @Transactional
    public StatusPageComponent createComponent(ComponentCommand command, Long tenantId, Long actorId) {
        StatusPageComponent component = new StatusPageComponent();
        applyComponent(component, command, tenantId);
        LocalDateTime now = LocalDateTime.now();
        component.setCreatedBy(actorId);
        component.setCreatedAt(now);
        component.setUpdatedAt(now);
        try {
            componentMapper.insert(component);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("服务组件名称或公开标识已存在");
        }
        auditLogService.record(tenantId, actorId, "STATUS_PAGE_COMPONENT", component.getId(),
                "STATUS_COMPONENT_CREATED", null, component, "创建公开服务组件");
        return component;
    }

    @Transactional
    public StatusPageComponent updateComponent(Long id, ComponentCommand command, Long tenantId, Long actorId) {
        StatusPageComponent component = getComponent(id, tenantId);
        StatusPageComponent before = copyComponent(component);
        applyComponent(component, command, tenantId);
        component.setUpdatedAt(LocalDateTime.now());
        try {
            if (componentMapper.updateById(component) == 0) throw new BusinessException("服务组件更新失败，请刷新后重试");
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("服务组件名称或公开标识已存在");
        }
        auditLogService.record(tenantId, actorId, "STATUS_PAGE_COMPONENT", id,
                "STATUS_COMPONENT_UPDATED", before, component, "更新公开服务组件");
        return component;
    }

    @Transactional
    public IncidentDetail createIncident(IncidentCommand command, Long tenantId, Long actorId) {
        validateSourceIncident(command.sourceIncidentId(), tenantId);
        List<StatusPageComponent> components = resolveComponents(command.componentIds(), tenantId);
        StatusPageIncident incident = new StatusPageIncident();
        incident.setTenantId(tenantId);
        incident.setPublicNo("SP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT));
        incident.setSourceIncidentId(command.sourceIncidentId());
        incident.setTitle(required(command.title(), "公告标题", 160));
        incident.setSummary(required(command.summary(), "公开摘要", 2000));
        if (command.impact() == null) throw new BusinessException("请选择影响等级");
        incident.setImpact(command.impact());
        incident.setStatus(StatusPageIncidentStatus.INVESTIGATING);
        incident.setPublished(false);
        incident.setCreatedBy(actorId);
        LocalDateTime now = LocalDateTime.now();
        incident.setCreatedAt(now);
        incident.setUpdatedAt(now);
        incidentMapper.insert(incident);
        linkComponents(incident.getId(), components, tenantId, now);
        addUpdate(incident, StatusPageIncidentStatus.INVESTIGATING, "我们正在调查此问题。", actorId, tenantId, now);
        auditLogService.record(tenantId, actorId, "STATUS_PAGE_INCIDENT", incident.getId(),
                "STATUS_INCIDENT_CREATED", null, incidentAuditView(incident, components),
                "创建服务状态公告草稿；尚未对外发布");
        return incidentDetail(incident, components, tenantId);
    }

    @Transactional
    public IncidentDetail updateIncident(Long id, UpdateCommand command, Long tenantId, Long actorId) {
        StatusPageIncident incident = getIncident(id, tenantId);
        if (incident.getStatus() == StatusPageIncidentStatus.RESOLVED) {
            throw new BusinessException("已解决的服务公告不能再追加进展");
        }
        if (command.status() == null) throw new BusinessException("请选择公告状态");
        validateTransition(incident.getStatus(), command.status());
        String message = required(command.message(), "公开进展", 1000);
        StatusPageIncident before = copyIncident(incident);
        LocalDateTime now = LocalDateTime.now();
        incident.setStatus(command.status());
        if (command.status() == StatusPageIncidentStatus.RESOLVED) incident.setResolvedAt(now);
        incident.setUpdatedAt(now);
        incidentMapper.updateById(incident);
        addUpdate(incident, command.status(), message, actorId, tenantId, now);
        if (Boolean.TRUE.equals(incident.getPublished())) refreshLinkedComponents(incident, tenantId);
        auditLogService.record(tenantId, actorId, "STATUS_PAGE_INCIDENT", id,
                command.status() == StatusPageIncidentStatus.RESOLVED ? "STATUS_INCIDENT_RESOLVED" : "STATUS_INCIDENT_UPDATED",
                before, incident, "更新服务状态公告进展");
        return incidentDetail(incident, null, tenantId);
    }

    @Transactional
    public IncidentDetail publish(Long id, Long tenantId, Long actorId) {
        StatusPageIncident incident = getIncident(id, tenantId);
        if (Boolean.TRUE.equals(incident.getPublished())) return incidentDetail(incident, null, tenantId);
        LocalDateTime now = LocalDateTime.now();
        incident.setPublished(true);
        incident.setPublishedBy(actorId);
        incident.setPublishedAt(now);
        incident.setUpdatedAt(now);
        incidentMapper.updateById(incident);
        refreshLinkedComponents(incident, tenantId);
        auditLogService.record(tenantId, actorId, "STATUS_PAGE_INCIDENT", id,
                "STATUS_INCIDENT_PUBLISHED", false, true, "将服务状态公告发布到公开状态页");
        return incidentDetail(incident, null, tenantId);
    }

    @Transactional
    public IncidentDetail unpublish(Long id, Long tenantId, Long actorId) {
        StatusPageIncident incident = getIncident(id, tenantId);
        if (!Boolean.TRUE.equals(incident.getPublished())) return incidentDetail(incident, null, tenantId);
        incident.setPublished(false);
        incident.setUpdatedAt(LocalDateTime.now());
        incidentMapper.updateById(incident);
        refreshLinkedComponents(incident, tenantId);
        auditLogService.record(tenantId, actorId, "STATUS_PAGE_INCIDENT", id,
                "STATUS_INCIDENT_UNPUBLISHED", true, false, "从公开状态页撤回服务状态公告");
        return incidentDetail(incident, null, tenantId);
    }

    public PublicStatusPage publicSnapshot(Long tenantId) {
        List<StatusPageComponent> components = componentMapper.selectList(new LambdaQueryWrapper<StatusPageComponent>()
                .eq(StatusPageComponent::getTenantId, tenantId)
                .eq(StatusPageComponent::getEnabled, true)
                .orderByAsc(StatusPageComponent::getDisplayOrder)
                .orderByAsc(StatusPageComponent::getName));
        List<StatusPageIncident> incidents = incidentMapper.selectList(new LambdaQueryWrapper<StatusPageIncident>()
                .eq(StatusPageIncident::getTenantId, tenantId)
                .eq(StatusPageIncident::getPublished, true)
                .orderByDesc(StatusPageIncident::getPublishedAt)
                .orderByDesc(StatusPageIncident::getId)
                .last("LIMIT 30"));
        Map<Long, List<StatusPageComponent>> componentLinks = componentLinks(incidents, components, tenantId);
        List<PublicComponent> publicComponents = components.stream().map(component -> new PublicComponent(
                component.getSlug(), component.getName(), component.getDescription(), component.getStatus(),
                component.getStatusMessage(), component.getUpdatedAt())).toList();
        List<PublicIncident> publicIncidents = incidents.stream().map(incident -> new PublicIncident(
                incident.getPublicNo(), incident.getTitle(), incident.getSummary(), incident.getImpact(), incident.getStatus(),
                incident.getPublishedAt(), incident.getResolvedAt(),
                componentLinks.getOrDefault(incident.getId(), List.of()).stream().map(StatusPageComponent::getSlug).toList(),
                updateViews(incident.getId(), tenantId))).toList();
        LocalDateTime updatedAt = newestUpdatedAt(components, incidents);
        return new PublicStatusPage(overallStatus(components), overallMessage(components), updatedAt,
                publicComponents, publicIncidents);
    }

    public PublicIncident publicIncident(String publicNo, Long tenantId) {
        StatusPageIncident incident = incidentMapper.selectOne(new LambdaQueryWrapper<StatusPageIncident>()
                .eq(StatusPageIncident::getTenantId, tenantId)
                .eq(StatusPageIncident::getPublicNo, publicNo)
                .eq(StatusPageIncident::getPublished, true));
        if (incident == null) throw new BusinessException("公开服务公告不存在");
        List<StatusPageComponent> enabledComponents = componentMapper.selectList(new LambdaQueryWrapper<StatusPageComponent>()
                .eq(StatusPageComponent::getTenantId, tenantId)
                .eq(StatusPageComponent::getEnabled, true));
        Map<Long, List<StatusPageComponent>> links = componentLinks(List.of(incident), enabledComponents, tenantId);
        return new PublicIncident(incident.getPublicNo(), incident.getTitle(), incident.getSummary(), incident.getImpact(),
                incident.getStatus(), incident.getPublishedAt(), incident.getResolvedAt(),
                links.getOrDefault(incident.getId(), List.of()).stream().map(StatusPageComponent::getSlug).toList(),
                updateViews(incident.getId(), tenantId));
    }

    private void applyComponent(StatusPageComponent component, ComponentCommand command, Long tenantId) {
        String slug = normalizeSlug(command.slug());
        component.setTenantId(tenantId);
        component.setSlug(slug);
        component.setName(required(command.name(), "服务组件名称", 120));
        component.setDescription(optional(command.description(), 1000, "服务组件说明"));
        if (command.assetId() != null) cmdbAssetService.get(command.assetId(), tenantId);
        component.setAssetId(command.assetId());
        component.setStatus(command.status() == null ? StatusPageComponentStatus.OPERATIONAL : command.status());
        component.setStatusMessage(optional(command.statusMessage(), 300, "状态说明"));
        component.setDisplayOrder(command.displayOrder() == null ? 100 : Math.max(0, Math.min(9999, command.displayOrder())));
        component.setEnabled(command.enabled() == null || command.enabled());
    }

    private void linkComponents(Long incidentId, List<StatusPageComponent> components, Long tenantId, LocalDateTime now) {
        for (StatusPageComponent component : components) {
            StatusPageIncidentComponent link = new StatusPageIncidentComponent();
            link.setTenantId(tenantId);
            link.setStatusIncidentId(incidentId);
            link.setComponentId(component.getId());
            link.setCreatedAt(now);
            incidentComponentMapper.insert(link);
        }
    }

    private void addUpdate(StatusPageIncident incident, StatusPageIncidentStatus status, String message,
                           Long actorId, Long tenantId, LocalDateTime now) {
        StatusPageUpdate update = new StatusPageUpdate();
        update.setTenantId(tenantId);
        update.setStatusIncidentId(incident.getId());
        update.setStatus(status);
        update.setMessage(message);
        update.setCreatedBy(actorId);
        update.setCreatedAt(now);
        updateMapper.insert(update);
        auditLogService.record(tenantId, actorId, "STATUS_PAGE_INCIDENT", incident.getId(),
                "STATUS_INCIDENT_UPDATE_POSTED", null, Map.of("status", status, "message", message),
                "记录公开服务公告进展");
    }

    private void refreshLinkedComponents(StatusPageIncident incident, Long tenantId) {
        List<StatusPageIncidentComponent> links = incidentComponentMapper.selectList(
                new LambdaQueryWrapper<StatusPageIncidentComponent>()
                        .eq(StatusPageIncidentComponent::getTenantId, tenantId)
                        .eq(StatusPageIncidentComponent::getStatusIncidentId, incident.getId()));
        for (StatusPageIncidentComponent link : links) refreshComponent(link.getComponentId(), tenantId);
    }

    /** Public component state is derived only from currently published, unresolved status announcements. */
    private void refreshComponent(Long componentId, Long tenantId) {
        StatusPageComponent component = getComponent(componentId, tenantId);
        List<StatusPageIncidentComponent> links = incidentComponentMapper.selectList(
                new LambdaQueryWrapper<StatusPageIncidentComponent>()
                        .eq(StatusPageIncidentComponent::getTenantId, tenantId)
                        .eq(StatusPageIncidentComponent::getComponentId, componentId));
        if (links.isEmpty()) return;
        Set<Long> incidentIds = links.stream().map(StatusPageIncidentComponent::getStatusIncidentId).collect(Collectors.toSet());
        List<StatusPageIncident> active = incidentMapper.selectBatchIds(incidentIds).stream()
                .filter(item -> tenantId.equals(item.getTenantId()))
                .filter(item -> Boolean.TRUE.equals(item.getPublished()))
                .filter(item -> item.getStatus() != StatusPageIncidentStatus.RESOLVED)
                .toList();
        LocalDateTime now = LocalDateTime.now();
        if (active.isEmpty()) {
            component.setStatus(StatusPageComponentStatus.OPERATIONAL);
            component.setStatusMessage("全部服务正常");
        } else {
            StatusPageIncident mostSevere = active.stream()
                    .max(Comparator.comparingInt(item -> impactRank(item.getImpact()))).orElseThrow();
            component.setStatus(componentStatus(mostSevere.getImpact()));
            component.setStatusMessage("正在处理：" + mostSevere.getTitle());
        }
        component.setUpdatedAt(now);
        componentMapper.updateById(component);
    }

    private List<StatusPageComponent> resolveComponents(Collection<Long> ids, Long tenantId) {
        if (ids == null || ids.isEmpty()) throw new BusinessException("请至少选择一个受影响的服务组件");
        Set<Long> uniqueIds = ids.stream().filter(item -> item != null).collect(Collectors.toCollection(HashSet::new));
        if (uniqueIds.isEmpty()) throw new BusinessException("请至少选择一个受影响的服务组件");
        List<StatusPageComponent> components = componentMapper.selectBatchIds(uniqueIds).stream()
                .filter(item -> tenantId.equals(item.getTenantId())).toList();
        if (components.size() != uniqueIds.size()) throw new BusinessException("包含不存在或不属于当前租户的服务组件");
        return components;
    }

    private void validateSourceIncident(Long sourceIncidentId, Long tenantId) {
        if (sourceIncidentId == null) return;
        Incident source = internalIncidentMapper.selectOne(new LambdaQueryWrapper<Incident>()
                .eq(Incident::getId, sourceIncidentId)
                .eq(Incident::getTenantId, tenantId));
        if (source == null) throw new BusinessException("关联的内部事故不存在");
    }

    private StatusPageComponent getComponent(Long id, Long tenantId) {
        StatusPageComponent component = componentMapper.selectOne(new LambdaQueryWrapper<StatusPageComponent>()
                .eq(StatusPageComponent::getId, id).eq(StatusPageComponent::getTenantId, tenantId));
        if (component == null) throw new BusinessException("服务组件不存在");
        return component;
    }

    private StatusPageIncident getIncident(Long id, Long tenantId) {
        StatusPageIncident incident = incidentMapper.selectOne(new LambdaQueryWrapper<StatusPageIncident>()
                .eq(StatusPageIncident::getId, id).eq(StatusPageIncident::getTenantId, tenantId));
        if (incident == null) throw new BusinessException("服务状态公告不存在");
        return incident;
    }

    private List<IncidentDetail> incidentDetails(List<StatusPageIncident> incidents,
                                                  List<StatusPageComponent> components, Long tenantId) {
        Map<Long, List<StatusPageComponent>> links = componentLinks(incidents, components, tenantId);
        return incidents.stream().map(incident -> incidentDetail(incident, links.get(incident.getId()), tenantId)).toList();
    }

    private IncidentDetail incidentDetail(StatusPageIncident incident, List<StatusPageComponent> knownComponents,
                                          Long tenantId) {
        List<StatusPageComponent> components = knownComponents == null
                ? componentsForIncident(incident.getId(), tenantId) : knownComponents;
        return new IncidentDetail(incident, components, updateViews(incident.getId(), tenantId));
    }

    private List<StatusPageComponent> componentsForIncident(Long incidentId, Long tenantId) {
        List<StatusPageIncidentComponent> links = incidentComponentMapper.selectList(
                new LambdaQueryWrapper<StatusPageIncidentComponent>()
                        .eq(StatusPageIncidentComponent::getTenantId, tenantId)
                        .eq(StatusPageIncidentComponent::getStatusIncidentId, incidentId));
        if (links.isEmpty()) return List.of();
        Set<Long> ids = links.stream().map(StatusPageIncidentComponent::getComponentId).collect(Collectors.toSet());
        Map<Long, StatusPageComponent> components = componentMapper.selectBatchIds(ids).stream()
                .filter(item -> tenantId.equals(item.getTenantId()))
                .collect(Collectors.toMap(StatusPageComponent::getId, item -> item));
        return links.stream().map(StatusPageIncidentComponent::getComponentId).map(components::get)
                .filter(item -> item != null).toList();
    }

    private Map<Long, List<StatusPageComponent>> componentLinks(List<StatusPageIncident> incidents,
                                                                  List<StatusPageComponent> allowedComponents,
                                                                  Long tenantId) {
        if (incidents.isEmpty() || allowedComponents.isEmpty()) return Map.of();
        Set<Long> incidentIds = incidents.stream().map(StatusPageIncident::getId).collect(Collectors.toSet());
        Set<Long> allowedIds = allowedComponents.stream().map(StatusPageComponent::getId).collect(Collectors.toSet());
        Map<Long, StatusPageComponent> byId = allowedComponents.stream()
                .collect(Collectors.toMap(StatusPageComponent::getId, item -> item));
        Map<Long, List<StatusPageComponent>> links = new HashMap<>();
        incidentComponentMapper.selectList(new LambdaQueryWrapper<StatusPageIncidentComponent>()
                        .eq(StatusPageIncidentComponent::getTenantId, tenantId)
                        .in(StatusPageIncidentComponent::getStatusIncidentId, incidentIds))
                .forEach(link -> {
                    if (allowedIds.contains(link.getComponentId())) {
                        links.computeIfAbsent(link.getStatusIncidentId(), key -> new ArrayList<>()).add(byId.get(link.getComponentId()));
                    }
                });
        return links;
    }

    private List<PublicUpdate> updateViews(Long incidentId, Long tenantId) {
        return updateMapper.selectList(new LambdaQueryWrapper<StatusPageUpdate>()
                        .eq(StatusPageUpdate::getTenantId, tenantId)
                        .eq(StatusPageUpdate::getStatusIncidentId, incidentId)
                        .orderByAsc(StatusPageUpdate::getCreatedAt)
                        .orderByAsc(StatusPageUpdate::getId))
                .stream().map(update -> new PublicUpdate(update.getStatus(), update.getMessage(), update.getCreatedAt())).toList();
    }

    private void validateTransition(StatusPageIncidentStatus from, StatusPageIncidentStatus to) {
        if (from == to) return;
        boolean allowed = switch (from) {
            case INVESTIGATING -> to == StatusPageIncidentStatus.IDENTIFIED
                    || to == StatusPageIncidentStatus.MONITORING || to == StatusPageIncidentStatus.RESOLVED;
            case IDENTIFIED -> to == StatusPageIncidentStatus.MONITORING || to == StatusPageIncidentStatus.RESOLVED;
            case MONITORING -> to == StatusPageIncidentStatus.IDENTIFIED || to == StatusPageIncidentStatus.RESOLVED;
            case RESOLVED -> false;
        };
        if (!allowed) throw new BusinessException("不允许的服务公告状态流转");
    }

    private StatusPageComponentStatus overallStatus(List<StatusPageComponent> components) {
        return components.stream().map(StatusPageComponent::getStatus)
                .max(Comparator.comparingInt(this::componentRank)).orElse(StatusPageComponentStatus.OPERATIONAL);
    }

    private String overallMessage(List<StatusPageComponent> components) {
        StatusPageComponentStatus status = overallStatus(components);
        return switch (status) {
            case OPERATIONAL -> "所有公开服务运行正常";
            case DEGRADED -> "部分服务性能下降，团队正在处理";
            case PARTIAL_OUTAGE -> "部分服务不可用，团队正在处理";
            case MAJOR_OUTAGE -> "核心服务受到影响，团队正在紧急处理";
            case MAINTENANCE -> "服务正在维护中";
        };
    }

    private int componentRank(StatusPageComponentStatus status) {
        return switch (status) {
            case OPERATIONAL -> 0;
            case MAINTENANCE -> 1;
            case DEGRADED -> 2;
            case PARTIAL_OUTAGE -> 3;
            case MAJOR_OUTAGE -> 4;
        };
    }

    private int impactRank(StatusPageIncidentImpact impact) {
        return switch (impact) {
            case MINOR -> 1;
            case MAJOR -> 2;
            case CRITICAL -> 3;
        };
    }

    private StatusPageComponentStatus componentStatus(StatusPageIncidentImpact impact) {
        return switch (impact) {
            case MINOR -> StatusPageComponentStatus.DEGRADED;
            case MAJOR -> StatusPageComponentStatus.PARTIAL_OUTAGE;
            case CRITICAL -> StatusPageComponentStatus.MAJOR_OUTAGE;
        };
    }

    private LocalDateTime newestUpdatedAt(List<StatusPageComponent> components, List<StatusPageIncident> incidents) {
        return java.util.stream.Stream.concat(components.stream().map(StatusPageComponent::getUpdatedAt),
                        incidents.stream().map(StatusPageIncident::getUpdatedAt))
                .filter(item -> item != null).max(LocalDateTime::compareTo).orElse(null);
    }

    private StatusPageComponent copyComponent(StatusPageComponent source) {
        StatusPageComponent copy = new StatusPageComponent();
        copy.setId(source.getId()); copy.setTenantId(source.getTenantId()); copy.setSlug(source.getSlug());
        copy.setName(source.getName()); copy.setDescription(source.getDescription()); copy.setAssetId(source.getAssetId());
        copy.setStatus(source.getStatus()); copy.setStatusMessage(source.getStatusMessage());
        copy.setDisplayOrder(source.getDisplayOrder()); copy.setEnabled(source.getEnabled()); copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt()); copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private StatusPageIncident copyIncident(StatusPageIncident source) {
        StatusPageIncident copy = new StatusPageIncident();
        copy.setId(source.getId()); copy.setTenantId(source.getTenantId()); copy.setPublicNo(source.getPublicNo());
        copy.setSourceIncidentId(source.getSourceIncidentId()); copy.setTitle(source.getTitle()); copy.setSummary(source.getSummary());
        copy.setImpact(source.getImpact()); copy.setStatus(source.getStatus()); copy.setPublished(source.getPublished());
        copy.setCreatedBy(source.getCreatedBy()); copy.setPublishedBy(source.getPublishedBy()); copy.setPublishedAt(source.getPublishedAt());
        copy.setResolvedAt(source.getResolvedAt()); copy.setCreatedAt(source.getCreatedAt()); copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private Map<String, Object> incidentAuditView(StatusPageIncident incident, List<StatusPageComponent> components) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicNo", incident.getPublicNo());
        view.put("title", incident.getTitle());
        view.put("impact", incident.getImpact());
        view.put("published", incident.getPublished());
        view.put("componentIds", components.stream().map(StatusPageComponent::getId).toList());
        return view;
    }

    private String normalizeSlug(String value) {
        String slug = required(value, "公开标识", 80).toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BusinessException("公开标识只能使用小写字母、数字和连字符");
        }
        return slug;
    }

    private String required(String value, String field, int maxLength) {
        String normalized = optional(value, maxLength, field);
        if (normalized == null) throw new BusinessException(field + "不能为空");
        return normalized;
    }

    private String optional(String value, int maxLength, String field) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isBlank()) return null;
        if (normalized.length() > maxLength) throw new BusinessException(field + "不能超过 " + maxLength + " 个字符");
        return normalized;
    }

    public record ComponentCommand(String slug, String name, String description, Long assetId,
                                   StatusPageComponentStatus status, String statusMessage,
                                   Integer displayOrder, Boolean enabled) { }

    public record IncidentCommand(String title, String summary, StatusPageIncidentImpact impact,
                                  List<Long> componentIds, Long sourceIncidentId) { }

    public record UpdateCommand(StatusPageIncidentStatus status, String message) { }

    public record StatusCenterView(List<StatusPageComponent> components, List<IncidentDetail> incidents) { }

    public record IncidentDetail(StatusPageIncident incident, List<StatusPageComponent> components,
                                 List<PublicUpdate> updates) { }

    public record PublicStatusPage(StatusPageComponentStatus status, String message, LocalDateTime updatedAt,
                                   List<PublicComponent> components, List<PublicIncident> incidents) { }

    public record PublicComponent(String slug, String name, String description, StatusPageComponentStatus status,
                                  String statusMessage, LocalDateTime updatedAt) { }

    public record PublicIncident(String publicNo, String title, String summary,
                                 StatusPageIncidentImpact impact, StatusPageIncidentStatus status,
                                 LocalDateTime publishedAt, LocalDateTime resolvedAt,
                                 List<String> componentSlugs, List<PublicUpdate> updates) { }

    public record PublicUpdate(StatusPageIncidentStatus status, String message, LocalDateTime createdAt) { }
}
