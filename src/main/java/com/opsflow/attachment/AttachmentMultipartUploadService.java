package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentMultipartUploadService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentMultipartUploadService.class);
    private static final int MAX_S3_PARTS = 10_000;
    private static final Duration COMPLETION_LEASE = Duration.ofSeconds(30);

    private final AttachmentUploadSessionMapper sessionMapper;
    private final TicketAttachmentMapper attachmentMapper;
    private final TicketService ticketService;
    private final AuditLogService auditLogService;
    private final AttachmentStorageService storageService;
    private final AttachmentStorageQuotaService quotaService;
    private final TransactionTemplate transactionTemplate;

    public AttachmentStorageService.MultipartCapabilities capabilities(Long ticketId, Long tenantId,
                                                                       Long requesterScope) {
        ticketService.getVisible(ticketId, tenantId, requesterScope);
        return storageService.multipartCapabilities();
    }

    public UploadSessionView initiate(Long ticketId, Long uploaderId, Long tenantId, Long requesterScope,
                                      String clientUploadId, String originalFilename, long sizeBytes) {
        ticketService.getVisible(ticketId, tenantId, requesterScope);
        AttachmentStorageService.MultipartCapabilities capabilities = storageService.multipartCapabilities();
        if (!capabilities.enabled()) throw new BusinessException("当前存储模式未启用大文件分片上传");
        if (sizeBytes <= capabilities.simpleUploadMaxBytes()) {
            throw new BusinessException("该文件可使用普通附件上传，无需创建分片会话");
        }
        if (sizeBytes > capabilities.multipartUploadMaxBytes()) {
            throw new BusinessException("附件大小超过分片上传上限");
        }
        String safeClientUploadId = normalizeClientUploadId(clientUploadId);
        AttachmentUploadSession existing = findByClientUploadId(tenantId, uploaderId, safeClientUploadId);
        if (existing != null) {
            ensureSameRequest(existing, ticketId, originalFilename, sizeBytes);
            return view(existing, loadParts(existing));
        }

        String originalName = AttachmentFilePolicy.safeOriginalName(originalFilename);
        String extension = AttachmentFilePolicy.extensionOf(originalName);
        String contentType = AttachmentFilePolicy.canonicalContentType(originalName);
        long partSize = effectivePartSize(sizeBytes, capabilities.partSize());
        int totalParts = Math.toIntExact((sizeBytes + partSize - 1) / partSize);
        String storedName = UUID.randomUUID() + "." + extension;
        String objectKey = "quarantine/" + tenantId + "/" + ticketId + "/" + storedName;
        AttachmentStorageType backend = storageService.configuredType();
        MultipartUploadObject provider = storageService.createMultipartUpload(objectKey, contentType,
                Map.of("tenant-id", String.valueOf(tenantId), "ticket-id", String.valueOf(ticketId),
                        "uploader-id", String.valueOf(uploaderId), "upload-mode", "multipart"));

        AttachmentUploadSession session = new AttachmentUploadSession();
        LocalDateTime now = LocalDateTime.now();
        session.setSessionKey(UUID.randomUUID().toString());
        session.setClientUploadId(safeClientUploadId);
        session.setTenantId(tenantId);
        session.setTicketId(ticketId);
        session.setUploaderId(uploaderId);
        session.setOriginalName(originalName);
        session.setStoredName(storedName);
        session.setContentType(contentType);
        session.setSizeBytes(sizeBytes);
        session.setStorageBackend(backend);
        session.setStoragePath(objectKey);
        session.setProviderUploadId(provider.uploadId());
        session.setStatus(AttachmentUploadStatus.INITIATED);
        session.setPartSize(partSize);
        session.setTotalParts(totalParts);
        session.setExpiresAt(now.plus(capabilities.sessionTtl()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                quotaService.assertCanStore(tenantId, sizeBytes);
                sessionMapper.insert(session);
                auditLogService.record(tenantId, uploaderId, "TICKET", ticketId,
                        "ATTACHMENT_MULTIPART_STARTED", null,
                        Map.of("sessionKey", session.getSessionKey(), "name", originalName,
                                "sizeBytes", sizeBytes, "parts", totalParts),
                        "创建大文件分片上传会话");
            });
        } catch (RuntimeException ex) {
            try {
                storageService.abortMultipartUpload(backend, objectKey, provider.uploadId());
            } catch (RuntimeException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
        }
        return view(session, List.of());
    }

    public UploadSessionView status(Long ticketId, String sessionKey, Long actorId, Long tenantId,
                                    Long requesterScope) {
        AttachmentUploadSession session = requireSession(sessionKey, tenantId);
        requireTicket(session, ticketId);
        requireOwner(session, actorId);
        ticketService.getVisible(session.getTicketId(), tenantId, requesterScope);
        if (isExpired(session) && !isTerminal(session.getStatus())) {
            expireForCleanup(session.getSessionKey(), tenantId);
            session = requireSession(sessionKey, tenantId);
        }
        return view(session, loadParts(session));
    }

    public UploadPartAccess partAccess(Long ticketId, String sessionKey, int partNumber, Long actorId,
                                       Long tenantId, Long requesterScope) {
        AttachmentUploadSession session = requireSession(sessionKey, tenantId);
        requireTicket(session, ticketId);
        requireOwner(session, actorId);
        ticketService.getVisible(session.getTicketId(), tenantId, requesterScope);
        requireActive(session);
        if (partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new BusinessException("分片编号超出上传会话范围");
        }
        Duration remaining = Duration.between(LocalDateTime.now(), session.getExpiresAt());
        try {
            var signed = storageService.presignUploadPart(session.getStorageBackend(), session.getStoragePath(),
                    session.getProviderUploadId(), partNumber, remaining);
            return new UploadPartAccess(partNumber, signed.uri().toString(), signed.expiresAt());
        } catch (AttachmentStorageException ex) {
            log.warn("Failed to sign multipart upload part {} for session {}", partNumber, sessionKey, ex);
            throw new BusinessException("生成分片上传地址失败，请稍后重试");
        }
    }

    public TicketAttachment complete(Long ticketId, String sessionKey, Long actorId, Long tenantId,
                                     Long requesterScope, List<CompletedMultipartPart> requestedParts) {
        AttachmentUploadSession visible = requireSession(sessionKey, tenantId);
        requireTicket(visible, ticketId);
        requireOwner(visible, actorId);
        ticketService.getVisible(visible.getTicketId(), tenantId, requesterScope);
        boolean materialized = visible.getStatus() == AttachmentUploadStatus.COMPLETING
                && providerObjectExists(visible);
        List<CompletedMultipartPart> parts = materialized
                ? List.of() : validateCompletedParts(requestedParts, visible.getTotalParts());

        AttachmentUploadSession session = transactionTemplate.execute(status -> {
            AttachmentUploadSession locked = requireLockedSession(sessionKey, tenantId);
            requireOwner(locked, actorId);
            if (locked.getStatus() == AttachmentUploadStatus.COMPLETED) return locked;
            if (locked.getStatus() != AttachmentUploadStatus.INITIATED
                    && locked.getStatus() != AttachmentUploadStatus.COMPLETING) {
                throw new BusinessException("当前上传会话不能执行合并");
            }
            if (isExpired(locked)) throw new BusinessException("上传会话已过期，请重新选择文件");
            LocalDateTime now = LocalDateTime.now();
            if (locked.getStatus() == AttachmentUploadStatus.COMPLETING && !materialized
                    && locked.getUpdatedAt() != null
                    && locked.getUpdatedAt().isAfter(now.minus(COMPLETION_LEASE))) {
                throw new BusinessException("分片正在合并，请稍后查询上传状态");
            }
            locked.setStatus(AttachmentUploadStatus.COMPLETING);
            locked.setFailureReason(null);
            locked.setUpdatedAt(now);
            sessionMapper.updateById(locked);
            return locked;
        });
        if (session == null) throw new BusinessException("上传会话不存在");
        if (session.getStatus() == AttachmentUploadStatus.COMPLETED) {
            return requireCompletedAttachment(session);
        }

        try {
            if (!storageService.exists(session.getStorageBackend(), session.getStoragePath())) {
                storageService.completeMultipartUpload(session.getStorageBackend(), session.getStoragePath(),
                        session.getProviderUploadId(), parts);
            }
            StoredObjectMetadata metadata = storageService.metadata(session.getStorageBackend(), session.getStoragePath());
            if (metadata.sizeBytes() != session.getSizeBytes()) {
                storageService.delete(session.getStorageBackend(), session.getStoragePath());
                markFailed(session, "合并后对象大小与声明不一致");
                throw new BusinessException("上传文件大小校验失败，请重新上传");
            }
            return finalizeAttachment(session, metadata, actorId);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            restoreInitiated(session, ex);
            log.warn("Multipart upload completion failed for session {}", sessionKey, ex);
            throw new BusinessException("分片合并失败，已保留上传进度，请稍后重试");
        }
    }

    public void abort(Long ticketId, String sessionKey, Long actorId, Long tenantId, Long requesterScope) {
        AttachmentUploadSession session = requireSession(sessionKey, tenantId);
        requireTicket(session, ticketId);
        requireOwner(session, actorId);
        ticketService.getVisible(session.getTicketId(), tenantId, requesterScope);
        if (session.getStatus() == AttachmentUploadStatus.ABORTED
                || session.getStatus() == AttachmentUploadStatus.EXPIRED) return;
        if (session.getStatus() == AttachmentUploadStatus.COMPLETED) {
            throw new BusinessException("已完成的附件不能通过上传会话取消");
        }
        AttachmentUploadStatus previous = claimCleanup(sessionKey, tenantId, AttachmentUploadStatus.ABORTING);
        if (isTerminal(previous)) return;
        try {
            cleanupProviderState(session);
            finishCleanup(sessionKey, tenantId, AttachmentUploadStatus.ABORTED, actorId);
        } catch (RuntimeException ex) {
            restoreCleanup(sessionKey, tenantId, previous, ex);
            throw new BusinessException("取消上传失败，请稍后重试");
        }
    }

    public void expireForCleanup(String sessionKey, Long tenantId) {
        AttachmentUploadSession session = requireSession(sessionKey, tenantId);
        if (isTerminal(session.getStatus())) return;
        AttachmentUploadStatus previous = claimCleanup(sessionKey, tenantId, AttachmentUploadStatus.EXPIRING);
        if (isTerminal(previous)) return;
        try {
            cleanupProviderState(session);
            finishCleanup(sessionKey, tenantId, AttachmentUploadStatus.EXPIRED, null);
        } catch (RuntimeException ex) {
            restoreCleanup(sessionKey, tenantId, previous, ex);
            throw ex;
        }
    }

    private TicketAttachment finalizeAttachment(AttachmentUploadSession session, StoredObjectMetadata metadata,
                                                Long actorId) {
        return transactionTemplate.execute(status -> {
            AttachmentUploadSession locked = requireLockedSession(session.getSessionKey(), session.getTenantId());
            if (locked.getStatus() == AttachmentUploadStatus.COMPLETED) {
                return requireCompletedAttachment(locked);
            }
            if (locked.getStatus() != AttachmentUploadStatus.COMPLETING) {
                throw new BusinessException("上传会话状态已变化，请刷新后重试");
            }
            LocalDateTime now = LocalDateTime.now();
            TicketAttachment attachment = new TicketAttachment();
            attachment.setTenantId(locked.getTenantId());
            attachment.setTicketId(locked.getTicketId());
            attachment.setUploaderId(locked.getUploaderId());
            attachment.setOriginalName(locked.getOriginalName());
            attachment.setStoredName(locked.getStoredName());
            attachment.setContentType(locked.getContentType());
            attachment.setSizeBytes(locked.getSizeBytes());
            attachment.setStoragePath(locked.getStoragePath());
            attachment.setStorageBackend(locked.getStorageBackend());
            attachment.setStorageEtag(metadata.etag());
            attachment.setStorageVersion(metadata.versionId());
            attachment.setScanStatus(AttachmentScanStatus.PENDING);
            attachment.setScanAttempts(0);
            attachment.setNextScanAt(now);
            attachment.setQuarantinedAt(now);
            attachment.setCreatedAt(now);
            attachment.setUpdatedAt(now);
            attachmentMapper.insert(attachment);

            locked.setStatus(AttachmentUploadStatus.COMPLETED);
            locked.setAttachmentId(attachment.getId());
            locked.setFailureReason(null);
            locked.setCompletedAt(now);
            locked.setUpdatedAt(now);
            sessionMapper.updateById(locked);
            auditLogService.record(locked.getTenantId(), actorId, "TICKET", locked.getTicketId(),
                    "ATTACHMENT_MULTIPART_COMPLETED",
                    Map.of("sessionKey", locked.getSessionKey()),
                    Map.of("attachmentId", attachment.getId(), "name", attachment.getOriginalName(),
                            "sizeBytes", attachment.getSizeBytes(), "scanStatus", AttachmentScanStatus.PENDING),
                    "大文件分片合并完成并进入安全隔离区");
            return attachment;
        });
    }

    private void cleanupProviderState(AttachmentUploadSession session) {
        if (storageService.exists(session.getStorageBackend(), session.getStoragePath())) {
            storageService.delete(session.getStorageBackend(), session.getStoragePath());
        }
        storageService.abortMultipartUpload(session.getStorageBackend(), session.getStoragePath(),
                session.getProviderUploadId());
    }

    private AttachmentUploadStatus claimCleanup(String sessionKey, Long tenantId,
                                                AttachmentUploadStatus target) {
        return transactionTemplate.execute(status -> {
            AttachmentUploadSession locked = requireLockedSession(sessionKey, tenantId);
            if (isTerminal(locked.getStatus())) return locked.getStatus();
            AttachmentUploadStatus previous = locked.getStatus() == AttachmentUploadStatus.ABORTING
                    || locked.getStatus() == AttachmentUploadStatus.EXPIRING
                    ? AttachmentUploadStatus.INITIATED : locked.getStatus();
            locked.setStatus(target);
            locked.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(locked);
            return previous;
        });
    }

    private void finishCleanup(String sessionKey, Long tenantId, AttachmentUploadStatus target, Long actorId) {
        transactionTemplate.executeWithoutResult(status -> {
            AttachmentUploadSession locked = requireLockedSession(sessionKey, tenantId);
            locked.setStatus(target);
            locked.setFailureReason(null);
            locked.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(locked);
            auditLogService.record(tenantId, actorId, "TICKET", locked.getTicketId(),
                    target == AttachmentUploadStatus.ABORTED
                            ? "ATTACHMENT_MULTIPART_ABORTED" : "ATTACHMENT_MULTIPART_EXPIRED",
                    Map.of("sessionKey", sessionKey, "name", locked.getOriginalName()), null,
                    target == AttachmentUploadStatus.ABORTED ? "用户取消大文件上传" : "过期分片上传会话已清理");
        });
    }

    private void restoreCleanup(String sessionKey, Long tenantId, AttachmentUploadStatus previous,
                                RuntimeException failure) {
        transactionTemplate.executeWithoutResult(status -> {
            AttachmentUploadSession locked = requireLockedSession(sessionKey, tenantId);
            locked.setStatus(previous == null || isTerminal(previous)
                    ? AttachmentUploadStatus.INITIATED : previous);
            locked.setFailureReason(safeFailure(failure));
            locked.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(locked);
        });
    }

    private void markFailed(AttachmentUploadSession session, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            AttachmentUploadSession locked = requireLockedSession(session.getSessionKey(), session.getTenantId());
            locked.setStatus(AttachmentUploadStatus.FAILED);
            locked.setFailureReason(reason);
            locked.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(locked);
        });
    }

    private void restoreInitiated(AttachmentUploadSession session, RuntimeException failure) {
        transactionTemplate.executeWithoutResult(status -> {
            AttachmentUploadSession locked = requireLockedSession(session.getSessionKey(), session.getTenantId());
            if (locked.getStatus() == AttachmentUploadStatus.COMPLETING) {
                locked.setStatus(AttachmentUploadStatus.INITIATED);
                locked.setFailureReason(safeFailure(failure));
                locked.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(locked);
            }
        });
    }

    private List<CompletedMultipartPart> validateCompletedParts(List<CompletedMultipartPart> requested,
                                                                int totalParts) {
        if (requested == null || requested.size() != totalParts) {
            throw new BusinessException("提交的分片数量与上传会话不一致");
        }
        TreeMap<Integer, CompletedMultipartPart> normalized = new TreeMap<>();
        for (CompletedMultipartPart part : requested) {
            if (part == null || part.partNumber() < 1 || part.partNumber() > totalParts
                    || part.etag() == null || part.etag().isBlank() || part.etag().length() > 256
                    || part.etag().chars().anyMatch(Character::isISOControl)) {
                throw new BusinessException("提交的分片信息非法");
            }
            if (normalized.put(part.partNumber(), part) != null) {
                throw new BusinessException("分片编号不能重复");
            }
        }
        for (int partNumber = 1; partNumber <= totalParts; partNumber++) {
            if (!normalized.containsKey(partNumber)) throw new BusinessException("分片列表不完整");
        }
        return new ArrayList<>(normalized.values());
    }

    private List<MultipartUploadedPart> loadParts(AttachmentUploadSession session) {
        if (session.getStatus() != AttachmentUploadStatus.INITIATED
                && session.getStatus() != AttachmentUploadStatus.COMPLETING) return List.of();
        try {
            if (storageService.exists(session.getStorageBackend(), session.getStoragePath())) return List.of();
            return storageService.listMultipartParts(session.getStorageBackend(), session.getStoragePath(),
                    session.getProviderUploadId());
        } catch (AttachmentStorageException ex) {
            log.warn("Failed to inspect multipart session {}", session.getSessionKey(), ex);
            throw new BusinessException("读取分片上传进度失败，请稍后重试");
        }
    }

    private boolean providerObjectExists(AttachmentUploadSession session) {
        try {
            return storageService.exists(session.getStorageBackend(), session.getStoragePath());
        } catch (AttachmentStorageException ex) {
            log.warn("Failed to inspect materialized object for multipart session {}",
                    session.getSessionKey(), ex);
            throw new BusinessException("检查分片合并状态失败，请稍后重试");
        }
    }

    private AttachmentUploadSession findByClientUploadId(Long tenantId, Long uploaderId, String clientUploadId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<AttachmentUploadSession>()
                .eq(AttachmentUploadSession::getTenantId, tenantId)
                .eq(AttachmentUploadSession::getUploaderId, uploaderId)
                .eq(AttachmentUploadSession::getClientUploadId, clientUploadId));
    }

    private AttachmentUploadSession requireSession(String sessionKey, Long tenantId) {
        AttachmentUploadSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AttachmentUploadSession>()
                .eq(AttachmentUploadSession::getSessionKey, normalizeSessionKey(sessionKey))
                .eq(AttachmentUploadSession::getTenantId, tenantId));
        if (session == null) throw new BusinessException("上传会话不存在");
        return session;
    }

    private AttachmentUploadSession requireLockedSession(String sessionKey, Long tenantId) {
        AttachmentUploadSession session = sessionMapper.selectForUpdate(normalizeSessionKey(sessionKey), tenantId);
        if (session == null) throw new BusinessException("上传会话不存在");
        return session;
    }

    private TicketAttachment requireCompletedAttachment(AttachmentUploadSession session) {
        if (session.getAttachmentId() == null) throw new BusinessException("上传会话完成记录不完整");
        TicketAttachment attachment = attachmentMapper.selectById(session.getAttachmentId());
        if (attachment == null || !session.getTenantId().equals(attachment.getTenantId())) {
            throw new BusinessException("已完成附件不存在");
        }
        return attachment;
    }

    private UploadSessionView view(AttachmentUploadSession session, List<MultipartUploadedPart> parts) {
        long uploadedBytes = parts.stream().mapToLong(MultipartUploadedPart::sizeBytes).sum();
        return new UploadSessionView(session.getSessionKey(), session.getOriginalName(), session.getSizeBytes(),
                session.getStatus(), session.getPartSize(), session.getTotalParts(), parts, uploadedBytes,
                session.getAttachmentId(), session.getFailureReason(), session.getExpiresAt());
    }

    private void requireActive(AttachmentUploadSession session) {
        if (session.getStatus() != AttachmentUploadStatus.INITIATED) {
            throw new BusinessException("当前上传会话不能继续上传分片");
        }
        if (isExpired(session)) throw new BusinessException("上传会话已过期，请重新选择文件");
    }

    private void requireOwner(AttachmentUploadSession session, Long actorId) {
        if (actorId == null || !actorId.equals(session.getUploaderId())) {
            throw new BusinessException("只能继续本人创建的上传会话");
        }
    }

    private void requireTicket(AttachmentUploadSession session, Long ticketId) {
        if (ticketId == null || !ticketId.equals(session.getTicketId())) {
            throw new BusinessException("上传会话不存在");
        }
    }

    private void ensureSameRequest(AttachmentUploadSession existing, Long ticketId, String originalFilename,
                                   long sizeBytes) {
        String originalName = AttachmentFilePolicy.safeOriginalName(originalFilename);
        if (!existing.getTicketId().equals(ticketId) || !existing.getOriginalName().equals(originalName)
                || existing.getSizeBytes() != sizeBytes) {
            throw new BusinessException("上传幂等标识已被其他文件使用");
        }
    }

    private long effectivePartSize(long fileSize, long configuredPartSize) {
        long minimumForPartLimit = (fileSize + MAX_S3_PARTS - 1) / MAX_S3_PARTS;
        return Math.max(configuredPartSize, minimumForPartLimit);
    }

    private String normalizeClientUploadId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new BusinessException("客户端上传标识非法");
        }
        return value;
    }

    private String normalizeSessionKey(String value) {
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) {
            throw new BusinessException("上传会话标识非法");
        }
        return value;
    }

    private boolean isExpired(AttachmentUploadSession session) {
        return session.getExpiresAt() == null || !session.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private boolean isTerminal(AttachmentUploadStatus status) {
        return status == AttachmentUploadStatus.COMPLETED || status == AttachmentUploadStatus.ABORTED
                || status == AttachmentUploadStatus.EXPIRED;
    }

    private String safeFailure(Throwable failure) {
        String message = failure.getMessage();
        String result = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return result.length() > 1000 ? result.substring(0, 1000) : result;
    }

    public record UploadSessionView(String sessionKey, String originalName, long sizeBytes,
                                    AttachmentUploadStatus status, long partSize, int totalParts,
                                    List<MultipartUploadedPart> parts, long uploadedBytes,
                                    Long attachmentId, String failureReason, LocalDateTime expiresAt) { }

    public record UploadPartAccess(int partNumber, String url, java.time.Instant expiresAt) { }
}
