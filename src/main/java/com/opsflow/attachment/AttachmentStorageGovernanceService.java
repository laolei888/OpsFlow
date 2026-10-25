package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttachmentStorageGovernanceService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentStorageGovernanceService.class);
    private static final List<AttachmentUploadStatus> ACTIVE_UPLOAD_STATUSES = List.of(
            AttachmentUploadStatus.INITIATED, AttachmentUploadStatus.COMPLETING,
            AttachmentUploadStatus.ABORTING, AttachmentUploadStatus.EXPIRING,
            AttachmentUploadStatus.FAILED);

    private final AttachmentStorageFindingMapper findingMapper;
    private final AttachmentStoragePolicyMapper policyMapper;
    private final TicketAttachmentMapper attachmentMapper;
    private final AttachmentUploadSessionMapper sessionMapper;
    private final AttachmentStorageService storageService;
    private final AttachmentStorageProperties properties;
    private final AttachmentStorageQuotaService quotaService;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    public ReconcileResult reconcile(Long tenantId, Long actorId) {
        if (!properties.getGovernance().isEnabled()) throw new BusinessException("附件存储治理未启用");
        // MySQL DATETIME is second-precision in the current schema. Keep the scan watermark at the
        // same precision so freshly upserted findings are not immediately considered stale.
        LocalDateTime startedAt = LocalDateTime.now().withNano(0);
        Map<AttachmentStorageType, Map<String, ObjectReference>> references = references(tenantId);
        int maxObjects = properties.getGovernance().getMaxObjectsPerScan();
        int detected = 0;
        int missing = 0;
        int resolved = 0;
        boolean truncated = false;
        List<AttachmentStorageType> scannedBackends = new ArrayList<>();

        for (AttachmentStorageType backend : storageService.inventoryBackends()) {
            scannedBackends.add(backend);
            Map<String, ObjectReference> backendReferences = references.getOrDefault(backend, Map.of());
            InventoryBatch batch = inventory(backend, tenantId, maxObjects);
            truncated |= batch.truncated();
            Set<String> currentKeys = new HashSet<>();
            for (StoredObjectInventoryItem item : batch.items()) {
                if (item.latest() && !item.deleteMarker()) currentKeys.add(item.key());
                AttachmentStorageFindingType type = classify(item, backendReferences.get(item.key()));
                if (type == null) continue;
                upsertFinding(tenantId, backend, type, item, startedAt);
                detected++;
            }
            if (!batch.truncated()) {
                for (ObjectReference reference : backendReferences.values()) {
                    if (currentKeys.contains(reference.key())) continue;
                    upsertMissingFinding(tenantId, backend, reference, startedAt);
                    detected++;
                    missing++;
                }
                resolved += findingMapper.resolveNotSeen(tenantId, backend, startedAt, LocalDateTime.now());
            }
        }
        markReconciled(tenantId, startedAt);
        if (actorId != null) {
            auditLogService.record(tenantId, actorId, "ATTACHMENT_STORAGE", tenantId,
                    "ATTACHMENT_STORAGE_RECONCILED", null,
                    Map.of("detected", detected, "missing", missing, "resolved", resolved,
                            "truncated", truncated, "backends", scannedBackends),
                    "执行附件对象与数据库引用一致性巡检");
        }
        return new ReconcileResult(detected, missing, resolved, truncated, scannedBackends, startedAt);
    }

    public List<AttachmentStorageFinding> findings(Long tenantId, AttachmentStorageFindingStatus status,
                                                    AttachmentStorageFindingType type, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        LambdaQueryWrapper<AttachmentStorageFinding> query =
                new LambdaQueryWrapper<AttachmentStorageFinding>()
                        .eq(AttachmentStorageFinding::getTenantId, tenantId)
                        .orderByDesc(AttachmentStorageFinding::getLastSeenAt)
                        .orderByDesc(AttachmentStorageFinding::getId)
                        .last("LIMIT " + safeLimit);
        if (status != null) query.eq(AttachmentStorageFinding::getStatus, status);
        if (type != null) query.eq(AttachmentStorageFinding::getFindingType, type);
        return findingMapper.selectList(query);
    }

    public AttachmentStorageQuotaService.StorageUsageSummary summary(Long tenantId) {
        return quotaService.summary(tenantId);
    }

    public AttachmentStorageQuotaService.StorageUsageSummary updatePolicy(Long tenantId, Long actorId,
                                                                           long quotaBytes,
                                                                           int warningPercent) {
        return quotaService.updatePolicy(tenantId, actorId, quotaBytes, warningPercent);
    }

    public AttachmentStorageFinding deleteFinding(Long id, Long tenantId, Long actorId,
                                                   String expectedObjectKey, String reason) {
        String normalizedReason = operationReason(reason);
        AttachmentStorageFinding finding = transactionTemplate.execute(
                status -> claimDeletion(id, tenantId, expectedObjectKey));
        if (finding == null) throw new BusinessException("存储巡检记录不存在");
        try {
            storageService.deleteVersion(finding.getStorageBackend(), finding.getObjectKey(), finding.getVersionId());
            return transactionTemplate.execute(status -> finishDeletion(finding, actorId, normalizedReason));
        } catch (RuntimeException ex) {
            transactionTemplate.executeWithoutResult(status -> failDeletion(finding, ex));
            log.warn("Failed to delete governed attachment object {} version {}",
                    finding.getObjectKey(), finding.getVersionId(), ex);
            throw new BusinessException("对象清理失败，巡检记录已保留供重试");
        }
    }

    @Transactional
    public AttachmentStorageFinding ignoreFinding(Long id, Long tenantId, Long actorId, String reason) {
        String normalizedReason = operationReason(reason);
        AttachmentStorageFinding finding = findingMapper.selectForUpdate(id, tenantId);
        if (finding == null) throw new BusinessException("存储巡检记录不存在");
        if (finding.getStatus() != AttachmentStorageFindingStatus.OPEN
                && finding.getStatus() != AttachmentStorageFindingStatus.FAILED) {
            throw new BusinessException("当前巡检记录不能忽略");
        }
        LocalDateTime now = LocalDateTime.now();
        finding.setStatus(AttachmentStorageFindingStatus.IGNORED);
        finding.setHandledBy(actorId);
        finding.setHandlingReason(normalizedReason);
        finding.setResolvedAt(now);
        finding.setFailureReason(null);
        finding.setUpdatedAt(now);
        findingMapper.updateById(finding);
        auditLogService.record(tenantId, actorId, "ATTACHMENT_STORAGE_FINDING", id,
                "ATTACHMENT_STORAGE_FINDING_IGNORED", null,
                Map.of("type", finding.getFindingType(), "backend", finding.getStorageBackend(),
                        "objectKey", finding.getObjectKey()), normalizedReason);
        return finding;
    }

    private AttachmentStorageFinding claimDeletion(Long id, Long tenantId, String expectedObjectKey) {
        AttachmentStorageFinding finding = findingMapper.selectForUpdate(id, tenantId);
        if (finding == null) return null;
        if (expectedObjectKey == null || !finding.getObjectKey().equals(expectedObjectKey)) {
            throw new BusinessException("对象键校验失败，请刷新巡检结果后重试");
        }
        if (finding.getStatus() != AttachmentStorageFindingStatus.OPEN
                && finding.getStatus() != AttachmentStorageFindingStatus.FAILED) {
            throw new BusinessException("当前巡检记录不能执行清理");
        }
        if (finding.getFindingType() == AttachmentStorageFindingType.MISSING_OBJECT) {
            throw new BusinessException("缺失对象告警不能执行删除");
        }
        if (finding.getEligibleDeleteAt() == null || finding.getEligibleDeleteAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException("对象仍在安全观察期内，暂不能删除");
        }
        if (!safeToDelete(finding)) throw new BusinessException("对象已重新被业务引用，禁止删除");
        finding.setStatus(AttachmentStorageFindingStatus.DELETING);
        finding.setFailureReason(null);
        finding.setUpdatedAt(LocalDateTime.now());
        findingMapper.updateById(finding);
        return finding;
    }

    private AttachmentStorageFinding finishDeletion(AttachmentStorageFinding claimed, Long actorId,
                                                      String reason) {
        AttachmentStorageFinding finding = findingMapper.selectForUpdate(claimed.getId(), claimed.getTenantId());
        if (finding == null || finding.getStatus() != AttachmentStorageFindingStatus.DELETING) {
            throw new BusinessException("对象清理状态已变化");
        }
        LocalDateTime now = LocalDateTime.now();
        finding.setStatus(AttachmentStorageFindingStatus.DELETED);
        finding.setHandledBy(actorId);
        finding.setHandlingReason(reason);
        finding.setResolvedAt(now);
        finding.setFailureReason(null);
        finding.setUpdatedAt(now);
        findingMapper.updateById(finding);
        auditLogService.record(finding.getTenantId(), actorId, "ATTACHMENT_STORAGE_FINDING", finding.getId(),
                "ATTACHMENT_STORAGE_OBJECT_DELETED", null,
                Map.of("type", finding.getFindingType(), "backend", finding.getStorageBackend(),
                        "objectKey", finding.getObjectKey(), "versionId", safe(finding.getVersionId()),
                        "sizeBytes", finding.getSizeBytes()), reason);
        return finding;
    }

    private void failDeletion(AttachmentStorageFinding claimed, RuntimeException failure) {
        AttachmentStorageFinding finding = findingMapper.selectForUpdate(claimed.getId(), claimed.getTenantId());
        if (finding == null) return;
        finding.setStatus(AttachmentStorageFindingStatus.FAILED);
        finding.setFailureReason(safeFailure(failure));
        finding.setUpdatedAt(LocalDateTime.now());
        findingMapper.updateById(finding);
    }

    private boolean safeToDelete(AttachmentStorageFinding finding) {
        List<TicketAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<TicketAttachment>()
                        .eq(TicketAttachment::getTenantId, finding.getTenantId())
                        .eq(TicketAttachment::getStorageBackend, finding.getStorageBackend())
                        .eq(TicketAttachment::getStoragePath, finding.getObjectKey()));
        List<AttachmentUploadSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<AttachmentUploadSession>()
                        .eq(AttachmentUploadSession::getTenantId, finding.getTenantId())
                        .eq(AttachmentUploadSession::getStorageBackend, finding.getStorageBackend())
                        .eq(AttachmentUploadSession::getStoragePath, finding.getObjectKey())
                        .in(AttachmentUploadSession::getStatus, ACTIVE_UPLOAD_STATUSES));
        if (finding.getFindingType() != AttachmentStorageFindingType.NONCURRENT_VERSION) {
            return attachments.isEmpty() && sessions.isEmpty();
        }
        if (Boolean.TRUE.equals(finding.getLatestVersion()) && (!attachments.isEmpty() || !sessions.isEmpty())) {
            return false;
        }
        return attachments.stream().noneMatch(item -> sameVersion(item.getStorageVersion(), finding.getVersionId()));
    }

    private Map<AttachmentStorageType, Map<String, ObjectReference>> references(Long tenantId) {
        Map<AttachmentStorageType, Map<String, ObjectReference>> result =
                new EnumMap<>(AttachmentStorageType.class);
        List<TicketAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<TicketAttachment>().eq(TicketAttachment::getTenantId, tenantId)
                        .select(TicketAttachment::getStorageBackend, TicketAttachment::getStoragePath,
                                TicketAttachment::getStorageVersion, TicketAttachment::getSizeBytes));
        for (TicketAttachment attachment : attachments) {
            AttachmentStorageType backend = attachment.getStorageBackend() == null
                    ? AttachmentStorageType.LOCAL : attachment.getStorageBackend();
            result.computeIfAbsent(backend, ignored -> new HashMap<>()).put(attachment.getStoragePath(),
                    new ObjectReference(attachment.getStoragePath(), attachment.getStorageVersion(),
                            attachment.getSizeBytes() == null ? 0 : attachment.getSizeBytes()));
        }
        List<AttachmentUploadSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<AttachmentUploadSession>()
                        .eq(AttachmentUploadSession::getTenantId, tenantId)
                        .in(AttachmentUploadSession::getStatus, ACTIVE_UPLOAD_STATUSES)
                        .select(AttachmentUploadSession::getStorageBackend, AttachmentUploadSession::getStoragePath,
                                AttachmentUploadSession::getSizeBytes));
        for (AttachmentUploadSession session : sessions) {
            result.computeIfAbsent(session.getStorageBackend(), ignored -> new HashMap<>())
                    .putIfAbsent(session.getStoragePath(), new ObjectReference(session.getStoragePath(), null,
                            session.getSizeBytes() == null ? 0 : session.getSizeBytes()));
        }
        return result;
    }

    private InventoryBatch inventory(AttachmentStorageType backend, Long tenantId, int limit) {
        List<StoredObjectInventoryItem> items = new ArrayList<>();
        boolean truncated = false;
        for (String area : List.of("quarantine", "clean")) {
            int remaining = Math.max(1, limit - items.size());
            StoredObjectInventory inventory = storageService.inventory(backend,
                    area + "/" + tenantId + "/", remaining);
            items.addAll(inventory.items());
            truncated |= inventory.truncated();
            if (items.size() >= limit) {
                truncated = true;
                break;
            }
        }
        if (items.size() > limit) items = new ArrayList<>(items.subList(0, limit));
        return new InventoryBatch(items, truncated);
    }

    private AttachmentStorageFindingType classify(StoredObjectInventoryItem item, ObjectReference reference) {
        if (item.deleteMarker()) return reference == null ? AttachmentStorageFindingType.DELETE_MARKER : null;
        if (reference == null) return AttachmentStorageFindingType.ORPHAN_OBJECT;
        boolean protectedVersion = item.latest()
                || sameVersion(reference.versionId(), item.versionId());
        return protectedVersion ? null : AttachmentStorageFindingType.NONCURRENT_VERSION;
    }

    private void upsertFinding(Long tenantId, AttachmentStorageType backend,
                               AttachmentStorageFindingType type, StoredObjectInventoryItem item,
                               LocalDateTime detectedAt) {
        LocalDateTime objectModified = item.lastModified() == null ? null
                : LocalDateTime.ofInstant(item.lastModified(), ZoneId.systemDefault());
        LocalDateTime observationStart = objectModified != null && objectModified.isAfter(detectedAt)
                ? objectModified : detectedAt;
        LocalDateTime eligibleAt = observationStart.plus(properties.getGovernance().getOrphanGracePeriod());
        AttachmentStorageFinding finding = baseFinding(tenantId, backend, type, item.key(),
                item.versionId(), item.sizeBytes(), detectedAt);
        finding.setObjectLastModifiedAt(objectModified);
        finding.setLatestVersion(item.latest());
        finding.setEligibleDeleteAt(eligibleAt);
        findingMapper.upsertDetected(finding);
    }

    private void upsertMissingFinding(Long tenantId, AttachmentStorageType backend,
                                      ObjectReference reference, LocalDateTime detectedAt) {
        AttachmentStorageFinding finding = baseFinding(tenantId, backend,
                AttachmentStorageFindingType.MISSING_OBJECT, reference.key(), reference.versionId(),
                reference.sizeBytes(), detectedAt);
        finding.setLatestVersion(true);
        finding.setEligibleDeleteAt(null);
        findingMapper.upsertDetected(finding);
    }

    private AttachmentStorageFinding baseFinding(Long tenantId, AttachmentStorageType backend,
                                                   AttachmentStorageFindingType type, String key,
                                                   String versionId, long sizeBytes, LocalDateTime now) {
        AttachmentStorageFinding finding = new AttachmentStorageFinding();
        finding.setFindingFingerprint(fingerprint(type, backend, key, versionId));
        finding.setTenantId(tenantId);
        finding.setStorageBackend(backend);
        finding.setFindingType(type);
        finding.setStatus(AttachmentStorageFindingStatus.OPEN);
        finding.setObjectKey(key);
        finding.setVersionId(versionId);
        finding.setSizeBytes(Math.max(0, sizeBytes));
        finding.setDetectedAt(now);
        finding.setLastSeenAt(now);
        finding.setCreatedAt(now);
        finding.setUpdatedAt(now);
        return finding;
    }

    private void markReconciled(Long tenantId, LocalDateTime now) {
        policyMapper.insertDefault(tenantId, properties.getGovernance().getDefaultQuotaBytes(),
                properties.getGovernance().getDefaultWarningPercent(), now);
        AttachmentStoragePolicy policy = policyMapper.selectById(tenantId);
        if (policy == null) return;
        policy.setLastReconciledAt(now);
        policy.setUpdatedAt(now);
        policyMapper.updateById(policy);
    }

    private String fingerprint(AttachmentStorageFindingType type, AttachmentStorageType backend,
                               String key, String versionId) {
        String value = type + "|" + backend + "|" + key + "|" + safe(versionId);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    private String operationReason(String value) {
        if (value == null || value.trim().length() < 5 || value.trim().length() > 500) {
            throw new BusinessException("操作原因长度必须在 5-500 个字符之间");
        }
        return value.trim();
    }

    private boolean sameVersion(String left, String right) {
        return safe(left).equals(safe(right));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeFailure(Throwable failure) {
        String value = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private record ObjectReference(String key, String versionId, long sizeBytes) { }
    private record InventoryBatch(List<StoredObjectInventoryItem> items, boolean truncated) { }

    public record ReconcileResult(int detected, int missing, int resolved, boolean truncated,
                                  List<AttachmentStorageType> backends, LocalDateTime startedAt) { }
}
