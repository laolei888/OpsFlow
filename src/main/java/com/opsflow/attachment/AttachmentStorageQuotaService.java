package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AttachmentStorageQuotaService {
    private final AttachmentStoragePolicyMapper policyMapper;
    private final AttachmentStorageFindingMapper findingMapper;
    private final TicketAttachmentMapper attachmentMapper;
    private final AttachmentUploadSessionMapper sessionMapper;
    private final AttachmentStorageProperties properties;
    private final AuditLogService auditLogService;

    @Transactional
    public void assertCanStore(Long tenantId, long additionalBytes) {
        if (!properties.getGovernance().isEnabled()) return;
        if (tenantId == null || additionalBytes < 0) throw new BusinessException("附件配额校验参数非法");
        AttachmentStoragePolicy policy = lockedPolicy(tenantId);
        long used = attachmentMapper.sumSizeByTenant(tenantId);
        long reserved = sessionMapper.sumReservedSizeByTenant(tenantId);
        long projected = safeAdd(safeAdd(used, reserved), additionalBytes);
        if (projected > policy.getQuotaBytes()) {
            long remaining = Math.max(0, policy.getQuotaBytes() - safeAdd(used, reserved));
            throw new BusinessException("租户附件存储配额不足，当前最多还可上传 " + formatBytes(remaining));
        }
    }

    @Transactional
    public StorageUsageSummary summary(Long tenantId) {
        AttachmentStoragePolicy policy = policy(tenantId);
        long used = attachmentMapper.sumSizeByTenant(tenantId);
        long reserved = sessionMapper.sumReservedSizeByTenant(tenantId);
        long committed = safeAdd(used, reserved);
        double usagePercent = policy.getQuotaBytes() <= 0 ? 100
                : Math.min(100, committed * 100.0 / policy.getQuotaBytes());
        List<AttachmentStorageFinding> open = findingMapper.selectList(
                new LambdaQueryWrapper<AttachmentStorageFinding>()
                        .eq(AttachmentStorageFinding::getTenantId, tenantId)
                        .in(AttachmentStorageFinding::getStatus,
                                AttachmentStorageFindingStatus.OPEN, AttachmentStorageFindingStatus.FAILED));
        long reclaimableBytes = open.stream()
                .filter(item -> item.getFindingType() != AttachmentStorageFindingType.MISSING_OBJECT)
                .mapToLong(item -> item.getSizeBytes() == null ? 0 : item.getSizeBytes()).sum();
        long missingObjects = open.stream()
                .filter(item -> item.getFindingType() == AttachmentStorageFindingType.MISSING_OBJECT).count();
        return new StorageUsageSummary(policy.getQuotaBytes(), policy.getWarningPercent(), used, reserved,
                Math.max(0, policy.getQuotaBytes() - committed), usagePercent,
                attachmentMapper.countByTenant(tenantId), sessionMapper.countActiveByTenant(tenantId),
                open.size(), reclaimableBytes, missingObjects, policy.getLastReconciledAt(),
                usagePercent >= policy.getWarningPercent());
    }

    @Transactional
    public StorageUsageSummary updatePolicy(Long tenantId, Long actorId, long quotaBytes, int warningPercent) {
        if (quotaBytes < 1024 * 1024) throw new BusinessException("附件配额不能小于 1MB");
        if (warningPercent < 1 || warningPercent > 100) throw new BusinessException("预警阈值必须在 1-100 之间");
        AttachmentStoragePolicy policy = lockedPolicy(tenantId);
        long occupied = safeAdd(attachmentMapper.sumSizeByTenant(tenantId),
                sessionMapper.sumReservedSizeByTenant(tenantId));
        if (quotaBytes < occupied) throw new BusinessException("新配额不能小于当前已使用及预留空间");
        Map<String, Object> before = Map.of("quotaBytes", policy.getQuotaBytes(),
                "warningPercent", policy.getWarningPercent());
        policy.setQuotaBytes(quotaBytes);
        policy.setWarningPercent(warningPercent);
        policy.setUpdatedBy(actorId);
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        auditLogService.record(tenantId, actorId, "ATTACHMENT_STORAGE", tenantId,
                "ATTACHMENT_STORAGE_POLICY_UPDATED", before,
                Map.of("quotaBytes", quotaBytes, "warningPercent", warningPercent),
                "更新租户附件存储配额策略");
        return summary(tenantId);
    }

    private AttachmentStoragePolicy policy(Long tenantId) {
        ensurePolicy(tenantId);
        AttachmentStoragePolicy policy = policyMapper.selectById(tenantId);
        if (policy == null) throw new IllegalStateException("附件存储配额策略初始化失败");
        return policy;
    }

    private AttachmentStoragePolicy lockedPolicy(Long tenantId) {
        ensurePolicy(tenantId);
        AttachmentStoragePolicy policy = policyMapper.selectForUpdate(tenantId);
        if (policy == null) throw new IllegalStateException("附件存储配额策略初始化失败");
        return policy;
    }

    private void ensurePolicy(Long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        policyMapper.insertDefault(tenantId, properties.getGovernance().getDefaultQuotaBytes(),
                properties.getGovernance().getDefaultWarningPercent(), now);
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024);
        return String.format("%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }

    public record StorageUsageSummary(long quotaBytes, int warningPercent, long usedBytes,
                                      long reservedBytes, long remainingBytes, double usagePercent,
                                      long attachmentCount, long activeUploadCount, long openFindingCount,
                                      long reclaimableBytes, long missingObjectCount,
                                      LocalDateTime lastReconciledAt, boolean warning) { }
}
