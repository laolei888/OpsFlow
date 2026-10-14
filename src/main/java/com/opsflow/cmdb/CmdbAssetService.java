package com.opsflow.cmdb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketService;
import com.opsflow.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CmdbAssetService {
    private static final List<TicketStatus> ACTIVE_TICKET_STATUSES = List.of(
            TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CONFIRMATION);
    private static final Set<String> TICKET_LINK_TYPES = Set.of("AFFECTED", "HOSTED_ON", "RELATED");

    private final CmdbAssetMapper assetMapper;
    private final CmdbAssetRelationMapper relationMapper;
    private final TicketAssetLinkMapper ticketAssetLinkMapper;
    private final TicketMapper ticketMapper;
    private final TicketService ticketService;
    private final AuthService authService;
    private final AuditLogService auditLogService;

    public List<CmdbAsset> list(Long tenantId, String keyword, AssetType assetType,
                                AssetStatus status, AssetCriticality criticality,
                                boolean staffView, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<CmdbAsset> query = new LambdaQueryWrapper<CmdbAsset>()
                .eq(CmdbAsset::getTenantId, tenantId)
                .orderByAsc(CmdbAsset::getStatus)
                .orderByAsc(CmdbAsset::getName)
                .last("LIMIT " + safeLimit);
        if (!staffView) query.ne(CmdbAsset::getStatus, AssetStatus.RETIRED);
        if (assetType != null) query.eq(CmdbAsset::getAssetType, assetType);
        if (status != null) query.eq(CmdbAsset::getStatus, status);
        if (criticality != null) query.eq(CmdbAsset::getCriticality, criticality);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(wrapper -> wrapper.like(CmdbAsset::getAssetNo, search)
                    .or().like(CmdbAsset::getName, search)
                    .or().like(CmdbAsset::getBusinessSystem, search)
                    .or().like(CmdbAsset::getLocation, search));
        }
        return assetMapper.selectList(query);
    }

    public CmdbAsset get(Long id, Long tenantId) {
        CmdbAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<CmdbAsset>()
                .eq(CmdbAsset::getId, id)
                .eq(CmdbAsset::getTenantId, tenantId));
        if (asset == null) throw new BusinessException("资产不存在");
        return asset;
    }

    @Transactional
    public CmdbAsset create(AssetCommand command, Long tenantId, Long actorId) {
        CmdbAsset asset = new CmdbAsset();
        applyCommand(asset, command, tenantId, actorId);
        LocalDateTime now = LocalDateTime.now();
        asset.setCreatedBy(actorId);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        try {
            assetMapper.insert(asset);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("资产编号已存在，请使用其他编号");
        }
        auditLogService.record(tenantId, actorId, "CMDB_ASSET", asset.getId(),
                "CMDB_ASSET_CREATED", null, asset, "创建配置项资产");
        return asset;
    }

    @Transactional
    public CmdbAsset update(Long id, AssetCommand command, Long tenantId, Long actorId) {
        CmdbAsset asset = get(id, tenantId);
        CmdbAsset before = copy(asset);
        applyCommand(asset, command, tenantId, actorId);
        asset.setUpdatedAt(LocalDateTime.now());
        try {
            if (assetMapper.updateById(asset) == 0) throw new BusinessException("资产更新失败，请刷新后重试");
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("资产编号已存在，请使用其他编号");
        }
        auditLogService.record(tenantId, actorId, "CMDB_ASSET", id,
                "CMDB_ASSET_UPDATED", before, asset, "更新配置项资产");
        return asset;
    }

    public List<AssetRelationView> relations(Long assetId, Long tenantId) {
        CmdbAsset asset = get(assetId, tenantId);
        List<CmdbAssetRelation> relations = relationMapper.selectList(new LambdaQueryWrapper<CmdbAssetRelation>()
                .eq(CmdbAssetRelation::getTenantId, tenantId)
                .and(wrapper -> wrapper.eq(CmdbAssetRelation::getSourceAssetId, asset.getId())
                        .or().eq(CmdbAssetRelation::getTargetAssetId, asset.getId()))
                .orderByDesc(CmdbAssetRelation::getCreatedAt));
        return relationViews(relations, tenantId);
    }

    @Transactional
    public AssetRelationView createRelation(Long sourceAssetId, RelationCommand command,
                                             Long tenantId, Long actorId) {
        CmdbAsset source = get(sourceAssetId, tenantId);
        CmdbAsset target = get(command.targetAssetId(), tenantId);
        if (source.getId().equals(target.getId())) throw new BusinessException("不能建立资产与自身的关系");
        if (relationMapper.selectOne(new LambdaQueryWrapper<CmdbAssetRelation>()
                .eq(CmdbAssetRelation::getTenantId, tenantId)
                .eq(CmdbAssetRelation::getSourceAssetId, source.getId())
                .eq(CmdbAssetRelation::getTargetAssetId, target.getId())
                .eq(CmdbAssetRelation::getRelationType, command.relationType())) != null) {
            throw new BusinessException("相同的资产关系已经存在");
        }
        CmdbAssetRelation relation = new CmdbAssetRelation();
        relation.setTenantId(tenantId);
        relation.setSourceAssetId(source.getId());
        relation.setTargetAssetId(target.getId());
        relation.setRelationType(command.relationType());
        relation.setDescription(normalize(command.description()));
        relation.setCreatedBy(actorId);
        relation.setCreatedAt(LocalDateTime.now());
        try {
            relationMapper.insert(relation);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("相同的资产关系已经存在");
        }
        AssetRelationView view = relationView(relation, source, target);
        auditLogService.record(tenantId, actorId, "CMDB_ASSET", source.getId(),
                "CMDB_RELATION_CREATED", null, view, "建立资产依赖关系");
        return view;
    }

    @Transactional
    public void deleteRelation(Long relationId, Long tenantId, Long actorId) {
        CmdbAssetRelation relation = relationMapper.selectOne(new LambdaQueryWrapper<CmdbAssetRelation>()
                .eq(CmdbAssetRelation::getId, relationId)
                .eq(CmdbAssetRelation::getTenantId, tenantId));
        if (relation == null) throw new BusinessException("资产关系不存在");
        CmdbAsset source = get(relation.getSourceAssetId(), tenantId);
        CmdbAsset target = get(relation.getTargetAssetId(), tenantId);
        relationMapper.deleteById(relationId);
        auditLogService.record(tenantId, actorId, "CMDB_ASSET", source.getId(),
                "CMDB_RELATION_DELETED", relationView(relation, source, target), null, "解除资产依赖关系");
    }

    public ImpactView impact(Long assetId, Long tenantId) {
        CmdbAsset asset = get(assetId, tenantId);
        List<CmdbAssetRelation> relations = relationMapper.selectList(new LambdaQueryWrapper<CmdbAssetRelation>()
                .eq(CmdbAssetRelation::getTenantId, tenantId)
                .and(wrapper -> wrapper.eq(CmdbAssetRelation::getSourceAssetId, assetId)
                        .or().eq(CmdbAssetRelation::getTargetAssetId, assetId)));
        long dependencies = relations.stream().filter(item -> assetId.equals(item.getSourceAssetId())).count();
        long dependents = relations.stream().filter(item -> assetId.equals(item.getTargetAssetId())).count();
        List<TicketAssetLink> links = ticketAssetLinkMapper.selectList(new LambdaQueryWrapper<TicketAssetLink>()
                .eq(TicketAssetLink::getTenantId, tenantId)
                .eq(TicketAssetLink::getAssetId, assetId)
                .orderByDesc(TicketAssetLink::getCreatedAt));
        long activeTickets = 0;
        if (!links.isEmpty()) {
            List<Long> ticketIds = links.stream().map(TicketAssetLink::getTicketId).toList();
            activeTickets = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                    .eq(Ticket::getTenantId, tenantId)
                    .in(Ticket::getId, ticketIds)
                    .in(Ticket::getStatus, ACTIVE_TICKET_STATUSES));
        }
        return new ImpactView(asset, relations.size(), links.size(), activeTickets, dependencies, dependents);
    }

    public List<TicketAssetView> ticketAssets(Long ticketId, Long tenantId) {
        ticketService.get(ticketId, tenantId);
        List<TicketAssetLink> links = ticketAssetLinkMapper.selectList(new LambdaQueryWrapper<TicketAssetLink>()
                .eq(TicketAssetLink::getTenantId, tenantId)
                .eq(TicketAssetLink::getTicketId, ticketId)
                .orderByDesc(TicketAssetLink::getCreatedAt));
        if (links.isEmpty()) return List.of();
        Set<Long> ids = new HashSet<>();
        links.forEach(link -> ids.add(link.getAssetId()));
        Map<Long, CmdbAsset> assets = new HashMap<>();
        assetMapper.selectBatchIds(ids).forEach(asset -> {
            if (tenantId.equals(asset.getTenantId())) assets.put(asset.getId(), asset);
        });
        return links.stream()
                .filter(link -> assets.containsKey(link.getAssetId()))
                .map(link -> new TicketAssetView(link.getTicketId(), link.getAssetId(), link.getLinkType(),
                        link.getNote(), assets.get(link.getAssetId()), link.getCreatedAt()))
                .toList();
    }

    @Transactional
    public TicketAssetView linkTicketAsset(Long ticketId, TicketAssetCommand command,
                                           Long tenantId, Long actorId) {
        Ticket ticket = ticketService.get(ticketId, tenantId);
        CmdbAsset asset = get(command.assetId(), tenantId);
        String linkType = normalizeLinkType(command.linkType());
        TicketAssetLink existing = ticketAssetLinkMapper.selectOne(new LambdaQueryWrapper<TicketAssetLink>()
                .eq(TicketAssetLink::getTenantId, tenantId)
                .eq(TicketAssetLink::getTicketId, ticket.getId())
                .eq(TicketAssetLink::getAssetId, asset.getId()));
        if (existing != null) return new TicketAssetView(ticketId, asset.getId(), existing.getLinkType(),
                existing.getNote(), asset, existing.getCreatedAt());
        TicketAssetLink link = new TicketAssetLink();
        link.setTenantId(tenantId);
        link.setTicketId(ticketId);
        link.setAssetId(asset.getId());
        link.setLinkType(linkType);
        link.setNote(normalize(command.note()));
        link.setCreatedBy(actorId);
        link.setCreatedAt(LocalDateTime.now());
        ticketAssetLinkMapper.insert(link);
        TicketAssetView view = new TicketAssetView(ticketId, asset.getId(), link.getLinkType(), link.getNote(), asset,
                link.getCreatedAt());
        auditLogService.record(tenantId, actorId, "TICKET", ticketId,
                "CMDB_ASSET_LINKED", null, Map.of("assetId", asset.getId(), "assetNo", asset.getAssetNo(),
                        "linkType", link.getLinkType()), "关联配置项「" + asset.getName() + "」");
        return view;
    }

    @Transactional
    public void unlinkTicketAsset(Long ticketId, Long assetId, Long tenantId, Long actorId) {
        Ticket ticket = ticketService.get(ticketId, tenantId);
        CmdbAsset asset = get(assetId, tenantId);
        TicketAssetLink link = ticketAssetLinkMapper.selectOne(new LambdaQueryWrapper<TicketAssetLink>()
                .eq(TicketAssetLink::getTenantId, tenantId)
                .eq(TicketAssetLink::getTicketId, ticketId)
                .eq(TicketAssetLink::getAssetId, assetId));
        if (link == null) throw new BusinessException("工单尚未关联该资产");
        ticketAssetLinkMapper.delete(new LambdaQueryWrapper<TicketAssetLink>()
                .eq(TicketAssetLink::getTenantId, tenantId)
                .eq(TicketAssetLink::getTicketId, ticketId)
                .eq(TicketAssetLink::getAssetId, assetId));
        auditLogService.record(tenantId, actorId, "TICKET", ticket.getId(),
                "CMDB_ASSET_UNLINKED", Map.of("assetId", asset.getId(), "assetNo", asset.getAssetNo()), null,
                "解除配置项「" + asset.getName() + "」关联");
    }

    private void applyCommand(CmdbAsset asset, AssetCommand command, Long tenantId, Long actorId) {
        String assetNo = normalize(command.assetNo());
        String name = normalize(command.name());
        if (assetNo == null || assetNo.length() > 40) throw new BusinessException("资产编号不能为空且不能超过 40 个字符");
        if (name == null || name.length() > 120) throw new BusinessException("资产名称不能为空且不能超过 120 个字符");
        if (command.assetType() == null) throw new BusinessException("请选择资产类型");
        if (command.environment() == null) throw new BusinessException("请选择运行环境");
        if (command.status() == null) throw new BusinessException("请选择资产状态");
        if (command.criticality() == null) throw new BusinessException("请选择业务重要性");
        if (command.ownerId() != null && !authService.isStaffMember(command.ownerId(), tenantId)) {
            throw new BusinessException("资产负责人不存在或不是当前租户的客服账号");
        }
        String businessSystem = normalize(command.businessSystem());
        String location = normalize(command.location());
        String description = normalize(command.description());
        String metadata = normalize(command.metadataJson());
        if (businessSystem != null && businessSystem.length() > 120) throw new BusinessException("业务系统名称不能超过 120 个字符");
        if (location != null && location.length() > 120) throw new BusinessException("位置不能超过 120 个字符");
        if (description != null && description.length() > 1000) throw new BusinessException("资产说明不能超过 1000 个字符");
        if (metadata != null && metadata.length() > 4000) throw new BusinessException("扩展属性不能超过 4000 个字符");
        asset.setTenantId(tenantId);
        asset.setAssetNo(assetNo);
        asset.setName(name);
        asset.setAssetType(command.assetType());
        asset.setEnvironment(command.environment());
        asset.setStatus(command.status());
        asset.setCriticality(command.criticality());
        asset.setOwnerId(command.ownerId());
        asset.setBusinessSystem(businessSystem);
        asset.setLocation(location);
        asset.setDescription(description);
        asset.setMetadataJson(metadata);
    }

    private List<AssetRelationView> relationViews(List<CmdbAssetRelation> relations, Long tenantId) {
        if (relations.isEmpty()) return List.of();
        Set<Long> ids = new HashSet<>();
        relations.forEach(item -> {
            ids.add(item.getSourceAssetId());
            ids.add(item.getTargetAssetId());
        });
        Map<Long, CmdbAsset> assets = new HashMap<>();
        assetMapper.selectBatchIds(ids).forEach(asset -> {
            if (tenantId.equals(asset.getTenantId())) assets.put(asset.getId(), asset);
        });
        return relations.stream()
                .filter(item -> assets.containsKey(item.getSourceAssetId()) && assets.containsKey(item.getTargetAssetId()))
                .map(item -> relationView(item, assets.get(item.getSourceAssetId()), assets.get(item.getTargetAssetId())))
                .toList();
    }

    private AssetRelationView relationView(CmdbAssetRelation relation, CmdbAsset source, CmdbAsset target) {
        return new AssetRelationView(relation.getId(), source.getId(), source.getAssetNo(), source.getName(),
                target.getId(), target.getAssetNo(), target.getName(), relation.getRelationType(),
                relation.getDescription(), relation.getCreatedAt());
    }

    private CmdbAsset copy(CmdbAsset source) {
        CmdbAsset copy = new CmdbAsset();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setAssetNo(source.getAssetNo());
        copy.setName(source.getName());
        copy.setAssetType(source.getAssetType());
        copy.setEnvironment(source.getEnvironment());
        copy.setStatus(source.getStatus());
        copy.setCriticality(source.getCriticality());
        copy.setOwnerId(source.getOwnerId());
        copy.setBusinessSystem(source.getBusinessSystem());
        copy.setLocation(source.getLocation());
        copy.setDescription(source.getDescription());
        copy.setMetadataJson(source.getMetadataJson());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String normalizeLinkType(String raw) {
        String value = raw == null || raw.isBlank() ? "AFFECTED" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TICKET_LINK_TYPES.contains(value)) throw new BusinessException("无效的工单资产关联类型");
        return value;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    public record AssetCommand(String assetNo, String name, AssetType assetType,
                               AssetEnvironment environment, AssetStatus status,
                               AssetCriticality criticality, Long ownerId,
                               String businessSystem, String location,
                               String description, String metadataJson) { }

    public record RelationCommand(Long targetAssetId, AssetRelationType relationType, String description) { }

    public record TicketAssetCommand(Long assetId, String linkType, String note) { }

    public record AssetRelationView(Long id, Long sourceAssetId, String sourceAssetNo, String sourceName,
                                    Long targetAssetId, String targetAssetNo, String targetName,
                                    AssetRelationType relationType, String description,
                                    LocalDateTime createdAt) { }

    public record TicketAssetView(Long ticketId, Long assetId, String linkType, String note,
                                  CmdbAsset asset, LocalDateTime createdAt) { }

    public record ImpactView(CmdbAsset asset, long relationCount, long linkedTicketCount,
                             long activeTicketCount, long dependencyCount, long dependentCount) { }
}
