package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentScanService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentScanService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int CLAIM_LEASE_SECONDS = 120;

    private final TicketAttachmentMapper attachmentMapper;
    private final AttachmentThreatScanner threatScanner;
    private final AuditLogService auditLogService;
    private final AttachmentSecurityMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${opsflow.storage.upload-dir:/app/uploads}")
    private String uploadDir;

    public int scanPending(int batchSize) {
        int safeBatch = Math.max(1, Math.min(batchSize, 100));
        LocalDateTime now = LocalDateTime.now();
        List<TicketAttachment> due = attachmentMapper.selectList(new LambdaQueryWrapper<TicketAttachment>()
                .and(query -> query
                        .and(ready -> ready.eq(TicketAttachment::getScanStatus, AttachmentScanStatus.PENDING)
                                .le(TicketAttachment::getNextScanAt, now))
                        .or(stale -> stale.eq(TicketAttachment::getScanStatus, AttachmentScanStatus.SCANNING)
                                .le(TicketAttachment::getScanClaimedAt, now.minusSeconds(CLAIM_LEASE_SECONDS))))
                .orderByAsc(TicketAttachment::getNextScanAt)
                .orderByAsc(TicketAttachment::getId)
                .last("LIMIT " + safeBatch));
        int processed = 0;
        for (TicketAttachment item : due) {
            try {
                scanOne(item.getId(), item.getTenantId());
                processed++;
            } catch (Exception ex) {
                log.warn("Attachment security scan {} failed unexpectedly", item.getId(), ex);
            }
        }
        return processed;
    }

    public void scanOne(Long attachmentId, Long tenantId) {
        ScanAttempt attempt = transactionTemplate.execute(status -> claim(attachmentId, tenantId));
        if (attempt == null) return;
        try {
            AttachmentThreatScanner.ScanResult result = threatScanner.scan(resolveStoredPath(attempt.attachment()),
                    attempt.attachment());
            if (result.clean()) {
                transactionTemplate.executeWithoutResult(status -> completeClean(attempt, result));
                metrics.clean();
            } else {
                transactionTemplate.executeWithoutResult(status -> completeInfected(attempt, result));
                metrics.blocked();
            }
        } catch (Exception failure) {
            boolean terminal = transactionTemplate.execute(status -> completeFailure(attempt, failure));
            if (terminal) metrics.failed();
        }
    }

    public List<AttachmentView> search(Long tenantId, AttachmentScanStatus status, String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<TicketAttachment> query = new LambdaQueryWrapper<TicketAttachment>()
                .eq(TicketAttachment::getTenantId, tenantId)
                .orderByDesc(TicketAttachment::getUpdatedAt)
                .orderByDesc(TicketAttachment::getId)
                .last("LIMIT " + safeLimit);
        if (status != null) query.eq(TicketAttachment::getScanStatus, status);
        String normalizedKeyword = trim(keyword);
        if (normalizedKeyword != null) {
            query.and(item -> item.like(TicketAttachment::getOriginalName, normalizedKeyword)
                    .or().like(TicketAttachment::getSha256, normalizedKeyword));
        }
        return attachmentMapper.selectList(query).stream().map(this::toView).toList();
    }

    public ScanSummary summary(Long tenantId) {
        long pending = count(tenantId, AttachmentScanStatus.PENDING);
        long scanning = count(tenantId, AttachmentScanStatus.SCANNING);
        long clean = count(tenantId, AttachmentScanStatus.CLEAN);
        long infected = count(tenantId, AttachmentScanStatus.INFECTED);
        long failed = count(tenantId, AttachmentScanStatus.FAILED);
        long released = count(tenantId, AttachmentScanStatus.RELEASED);
        TicketAttachment oldest = attachmentMapper.selectOne(new LambdaQueryWrapper<TicketAttachment>()
                .eq(TicketAttachment::getTenantId, tenantId)
                .in(TicketAttachment::getScanStatus, List.of(AttachmentScanStatus.PENDING, AttachmentScanStatus.SCANNING))
                .orderByAsc(TicketAttachment::getCreatedAt)
                .last("LIMIT 1"));
        return new ScanSummary(pending, scanning, clean, infected, failed, released,
                oldest == null ? null : oldest.getCreatedAt());
    }

    public void refreshMetrics() {
        metrics.update(countAll(AttachmentScanStatus.PENDING) + countAll(AttachmentScanStatus.SCANNING),
                countAll(AttachmentScanStatus.INFECTED), countAll(AttachmentScanStatus.FAILED));
    }

    @Transactional
    public AttachmentView retry(Long id, Long tenantId, Long actorId, String reason) {
        String normalizedReason = operationReason(reason, "重试");
        TicketAttachment attachment = attachmentMapper.selectForUpdate(id, tenantId);
        if (attachment == null) throw new BusinessException("附件安全记录不存在");
        if (!List.of(AttachmentScanStatus.INFECTED, AttachmentScanStatus.FAILED).contains(attachment.getScanStatus())) {
            throw new BusinessException("只有感染或扫描失败的附件可以重新扫描");
        }
        requireStoredFile(attachment);
        AttachmentScanStatus before = attachment.getScanStatus();
        LocalDateTime now = LocalDateTime.now();
        attachment.setScanStatus(AttachmentScanStatus.PENDING);
        attachment.setScanEngine(null);
        attachment.setScanResult(null);
        attachment.setScanAttempts(0);
        attachment.setNextScanAt(now);
        attachment.setScanClaimToken(null);
        attachment.setScanClaimedAt(null);
        attachment.setScannedAt(null);
        attachment.setUpdatedAt(now);
        attachmentMapper.updateById(attachment);
        auditLogService.record(tenantId, actorId, "ATTACHMENT", id, "ATTACHMENT_SCAN_RETRY",
                Map.of("status", before, "sha256", safeValue(attachment.getSha256())),
                Map.of("status", AttachmentScanStatus.PENDING), normalizedReason);
        return toView(attachment);
    }

    @Transactional
    public AttachmentView release(Long id, Long tenantId, Long actorId, String expectedSha256, String reason) {
        String normalizedReason = operationReason(reason, "放行");
        TicketAttachment attachment = attachmentMapper.selectForUpdate(id, tenantId);
        if (attachment == null) throw new BusinessException("附件安全记录不存在");
        if (!List.of(AttachmentScanStatus.INFECTED, AttachmentScanStatus.FAILED).contains(attachment.getScanStatus())) {
            throw new BusinessException("只有隔离中的感染或失败附件可以人工放行");
        }
        if (attachment.getSha256() == null || expectedSha256 == null
                || !attachment.getSha256().equalsIgnoreCase(expectedSha256.trim())) {
            throw new BusinessException("附件哈希校验失败，请刷新后重新确认");
        }
        AttachmentScanStatus before = attachment.getScanStatus();
        String previousPath = attachment.getStoragePath();
        String cleanPath = cleanRelativePath(attachment);
        moveWithRollback(resolveStoredPath(attachment), storageRoot().resolve(cleanPath).normalize());
        LocalDateTime now = LocalDateTime.now();
        attachment.setStoragePath(cleanPath);
        attachment.setScanStatus(AttachmentScanStatus.RELEASED);
        attachment.setReleasedBy(actorId);
        attachment.setReleasedAt(now);
        attachment.setReleaseReason(normalizedReason);
        attachment.setNextScanAt(null);
        attachment.setScanClaimToken(null);
        attachment.setScanClaimedAt(null);
        attachment.setUpdatedAt(now);
        attachmentMapper.updateById(attachment);
        auditLogService.record(tenantId, actorId, "ATTACHMENT", id, "ATTACHMENT_MANUALLY_RELEASED",
                Map.of("status", before, "sha256", attachment.getSha256(), "storagePath", previousPath),
                Map.of("status", AttachmentScanStatus.RELEASED, "storagePath", cleanPath), normalizedReason);
        metrics.released();
        return toView(attachment);
    }

    private ScanAttempt claim(Long id, Long tenantId) {
        TicketAttachment attachment = attachmentMapper.selectForUpdate(id, tenantId);
        if (attachment == null) return null;
        LocalDateTime now = LocalDateTime.now();
        boolean stale = attachment.getScanStatus() == AttachmentScanStatus.SCANNING
                && attachment.getScanClaimedAt() != null
                && !attachment.getScanClaimedAt().isAfter(now.minusSeconds(CLAIM_LEASE_SECONDS));
        if (attachment.getScanStatus() != AttachmentScanStatus.PENDING && !stale) return null;
        if (!stale && attachment.getNextScanAt() != null && attachment.getNextScanAt().isAfter(now)) return null;
        int attemptNumber = (attachment.getScanAttempts() == null ? 0 : attachment.getScanAttempts()) + 1;
        String claimToken = UUID.randomUUID().toString();
        attachment.setScanStatus(AttachmentScanStatus.SCANNING);
        attachment.setScanAttempts(attemptNumber);
        attachment.setScanClaimToken(claimToken);
        attachment.setScanClaimedAt(now);
        attachment.setNextScanAt(now.plusSeconds(CLAIM_LEASE_SECONDS));
        attachment.setUpdatedAt(now);
        attachmentMapper.updateById(attachment);
        return new ScanAttempt(attachment, attemptNumber, claimToken);
    }

    private void completeClean(ScanAttempt attempt, AttachmentThreatScanner.ScanResult result) {
        TicketAttachment attachment = currentClaim(attempt);
        if (attachment == null) return;
        String cleanPath = cleanRelativePath(attachment);
        moveWithRollback(resolveStoredPath(attachment), storageRoot().resolve(cleanPath).normalize());
        LocalDateTime now = LocalDateTime.now();
        attachment.setStoragePath(cleanPath);
        attachment.setScanStatus(AttachmentScanStatus.CLEAN);
        applyResult(attachment, result, now);
        attachmentMapper.updateById(attachment);
        auditLogService.record(attachment.getTenantId(), null, "ATTACHMENT", attachment.getId(),
                "ATTACHMENT_SCAN_CLEAN", Map.of("status", AttachmentScanStatus.SCANNING),
                Map.of("status", AttachmentScanStatus.CLEAN, "sha256", result.sha256()), "附件安全扫描通过并自动放行");
    }

    private void completeInfected(ScanAttempt attempt, AttachmentThreatScanner.ScanResult result) {
        TicketAttachment attachment = currentClaim(attempt);
        if (attachment == null) return;
        LocalDateTime now = LocalDateTime.now();
        attachment.setScanStatus(AttachmentScanStatus.INFECTED);
        applyResult(attachment, result, now);
        attachmentMapper.updateById(attachment);
        auditLogService.record(attachment.getTenantId(), null, "ATTACHMENT", attachment.getId(),
                "ATTACHMENT_SCAN_BLOCKED", Map.of("status", AttachmentScanStatus.SCANNING),
                Map.of("status", AttachmentScanStatus.INFECTED, "engine", result.engine(),
                        "result", result.result(), "sha256", result.sha256()), "附件检测到威胁并保留在隔离区");
    }

    private boolean completeFailure(ScanAttempt attempt, Exception failure) {
        TicketAttachment attachment = currentClaim(attempt);
        if (attachment == null) return false;
        AttachmentThreatScanException known = failure instanceof AttachmentThreatScanException typed ? typed : null;
        boolean terminal = known != null && !known.retryable() || attempt.attemptNumber() >= MAX_ATTEMPTS;
        LocalDateTime now = LocalDateTime.now();
        attachment.setScanStatus(terminal ? AttachmentScanStatus.FAILED : AttachmentScanStatus.PENDING);
        attachment.setScanResult(safeError(failure));
        if (known != null && known.sha256() != null) attachment.setSha256(known.sha256());
        attachment.setNextScanAt(terminal ? null : now.plusSeconds(backoffSeconds(attempt.attemptNumber())));
        attachment.setScanClaimToken(null);
        attachment.setScanClaimedAt(null);
        attachment.setScannedAt(terminal ? now : null);
        attachment.setUpdatedAt(now);
        attachmentMapper.updateById(attachment);
        if (terminal) {
            auditLogService.record(attachment.getTenantId(), null, "ATTACHMENT", attachment.getId(),
                    "ATTACHMENT_SCAN_FAILED", Map.of("status", AttachmentScanStatus.SCANNING),
                    Map.of("status", AttachmentScanStatus.FAILED, "attempts", attempt.attemptNumber()),
                    "附件扫描多次失败，等待管理员处置");
        }
        return terminal;
    }

    private TicketAttachment currentClaim(ScanAttempt attempt) {
        TicketAttachment current = attachmentMapper.selectForUpdate(attempt.attachment().getId(),
                attempt.attachment().getTenantId());
        if (current == null || current.getScanStatus() != AttachmentScanStatus.SCANNING
                || !attempt.claimToken().equals(current.getScanClaimToken())) return null;
        return current;
    }

    private void applyResult(TicketAttachment attachment, AttachmentThreatScanner.ScanResult result, LocalDateTime now) {
        attachment.setScanEngine(result.engine());
        attachment.setScanResult(result.result());
        attachment.setSha256(result.sha256());
        attachment.setNextScanAt(null);
        attachment.setScanClaimToken(null);
        attachment.setScanClaimedAt(null);
        attachment.setScannedAt(now);
        attachment.setUpdatedAt(now);
    }

    private Path resolveStoredPath(TicketAttachment attachment) {
        Path root = storageRoot();
        Path target = root.resolve(attachment.getStoragePath()).normalize();
        if (!target.startsWith(root)) throw new BusinessException("附件路径非法");
        return target;
    }

    private String cleanRelativePath(TicketAttachment attachment) {
        return "clean/" + attachment.getTenantId() + "/" + attachment.getTicketId() + "/" + attachment.getStoredName();
    }

    private Path storageRoot() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    private void move(Path source, Path target) {
        Path root = storageRoot();
        if (!source.startsWith(root) || !target.startsWith(root)) throw new BusinessException("附件路径非法");
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) throw new BusinessException("安全存储目标已存在，请联系管理员");
            try {
                Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, target);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException("附件从隔离区放行失败");
        }
    }

    private void moveWithRollback(Path source, Path target) {
        move(source, target);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return;
                try {
                    move(target, source);
                } catch (RuntimeException ex) {
                    log.error("Failed to restore attachment file after transaction rollback: {} -> {}", target, source, ex);
                }
            }
        });
    }

    private void requireStoredFile(TicketAttachment attachment) {
        Path path = resolveStoredPath(attachment);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new BusinessException("隔离文件不存在或不可读");
    }

    private String operationReason(String value, String operation) {
        String result = trim(value);
        if (result == null || result.length() < 5 || result.length() > 500) {
            throw new BusinessException(operation + "原因需填写 5 到 500 个字符");
        }
        return result;
    }

    private AttachmentView toView(TicketAttachment item) {
        return new AttachmentView(item.getId(), item.getTicketId(), item.getUploaderId(), item.getOriginalName(),
                item.getContentType(), item.getSizeBytes(), item.getScanStatus(), item.getScanEngine(),
                item.getScanResult(), item.getSha256(), item.getScanAttempts(), item.getNextScanAt(),
                item.getScannedAt(), item.getQuarantinedAt(), item.getReleasedBy(), item.getReleasedAt(),
                item.getReleaseReason(), item.getCreatedAt(), item.getUpdatedAt());
    }

    private long count(Long tenantId, AttachmentScanStatus status) {
        return attachmentMapper.selectCount(new LambdaQueryWrapper<TicketAttachment>()
                .eq(TicketAttachment::getTenantId, tenantId)
                .eq(TicketAttachment::getScanStatus, status));
    }

    private long countAll(AttachmentScanStatus status) {
        return attachmentMapper.selectCount(new LambdaQueryWrapper<TicketAttachment>()
                .eq(TicketAttachment::getScanStatus, status));
    }

    private long backoffSeconds(int attempt) {
        return Math.min(30L * (1L << Math.min(6, Math.max(0, attempt - 1))), 1800L);
    }

    private String safeError(Exception failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    private record ScanAttempt(TicketAttachment attachment, int attemptNumber, String claimToken) { }

    public record AttachmentView(Long id, Long ticketId, Long uploaderId, String originalName,
                                 String contentType, Long sizeBytes, AttachmentScanStatus scanStatus,
                                 String scanEngine, String scanResult, String sha256, Integer scanAttempts,
                                 LocalDateTime nextScanAt, LocalDateTime scannedAt, LocalDateTime quarantinedAt,
                                 Long releasedBy, LocalDateTime releasedAt, String releaseReason,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record ScanSummary(long pending, long scanning, long clean, long infected, long failed,
                              long released, LocalDateTime oldestPendingAt) { }
}
