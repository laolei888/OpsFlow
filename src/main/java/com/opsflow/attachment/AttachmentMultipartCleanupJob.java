package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AttachmentMultipartCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(AttachmentMultipartCleanupJob.class);
    private static final List<AttachmentUploadStatus> ACTIVE_STATUSES = List.of(
            AttachmentUploadStatus.INITIATED, AttachmentUploadStatus.COMPLETING,
            AttachmentUploadStatus.ABORTING, AttachmentUploadStatus.EXPIRING,
            AttachmentUploadStatus.FAILED);
    private static final List<AttachmentUploadStatus> TERMINAL_STATUSES = List.of(
            AttachmentUploadStatus.COMPLETED, AttachmentUploadStatus.ABORTED,
            AttachmentUploadStatus.EXPIRED);

    private final AttachmentUploadSessionMapper sessionMapper;
    private final AttachmentMultipartUploadService uploadService;
    private final AttachmentStorageProperties storageProperties;

    @Scheduled(
            fixedDelayString = "${opsflow.storage.multipart.cleanup-interval-ms:900000}",
            initialDelayString = "${opsflow.storage.multipart.cleanup-initial-delay-ms:60000}")
    public void scheduledCleanup() {
        try {
            CleanupResult result = cleanup(LocalDateTime.now());
            if (result.expired() > 0 || result.removedRecords() > 0) {
                log.info("Multipart upload cleanup expired {} session(s) and removed {} retained record(s)",
                        result.expired(), result.removedRecords());
            }
        } catch (Exception ex) {
            log.error("Multipart upload cleanup failed", ex);
        }
    }

    CleanupResult cleanup(LocalDateTime now) {
        List<AttachmentUploadSession> expired = sessionMapper.selectList(
                new LambdaQueryWrapper<AttachmentUploadSession>()
                        .in(AttachmentUploadSession::getStatus, ACTIVE_STATUSES)
                        .le(AttachmentUploadSession::getExpiresAt, now)
                        .orderByAsc(AttachmentUploadSession::getExpiresAt)
                        .orderByAsc(AttachmentUploadSession::getId)
                        .last("LIMIT 100"));
        int cleaned = 0;
        for (AttachmentUploadSession session : expired) {
            try {
                uploadService.expireForCleanup(session.getSessionKey(), session.getTenantId());
                cleaned++;
            } catch (RuntimeException ex) {
                log.warn("Failed to clean expired multipart upload session {}", session.getSessionKey(), ex);
            }
        }
        int retentionDays = Math.max(1, storageProperties.getMultipart().getCompletedRetentionDays());
        int removed = sessionMapper.delete(new LambdaQueryWrapper<AttachmentUploadSession>()
                .in(AttachmentUploadSession::getStatus, TERMINAL_STATUSES)
                .lt(AttachmentUploadSession::getUpdatedAt, now.minusDays(retentionDays)));
        return new CleanupResult(cleaned, removed);
    }

    record CleanupResult(int expired, int removedRecords) { }
}
