package com.opsflow.attachment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(TicketAttachmentService.class);
    private final TicketAttachmentMapper attachmentMapper;
    private final TicketService ticketService;
    private final AuditLogService auditLogService;
    private final AttachmentStorageService storageService;

    public List<TicketAttachment> list(Long ticketId, Long tenantId, Long requesterId) {
        ticketService.getVisible(ticketId, tenantId, requesterId);
        return attachmentMapper.selectList(new LambdaQueryWrapper<TicketAttachment>()
                .eq(TicketAttachment::getTicketId, ticketId)
                .eq(TicketAttachment::getTenantId, tenantId)
                .orderByDesc(TicketAttachment::getCreatedAt));
    }

    @Transactional
    public TicketAttachment upload(Long ticketId, Long uploaderId, Long tenantId, Long requesterId, MultipartFile file) {
        ticketService.getVisible(ticketId, tenantId, requesterId);
        if (file == null || file.isEmpty()) throw new BusinessException("请选择要上传的文件");
        long maxFileSize = storageService.maxFileSize();
        if (file.getSize() > maxFileSize) {
            throw new BusinessException("附件大小不能超过 " + (maxFileSize / 1024 / 1024) + "MB");
        }

        String originalName = AttachmentFilePolicy.safeOriginalName(file.getOriginalFilename());
        String extension = AttachmentFilePolicy.extensionOf(originalName);
        byte[] content = readContent(file);
        String verifiedContentType = AttachmentFilePolicy.verifyBytes(content, originalName);

        String storedName = UUID.randomUUID() + "." + extension;
        String objectKey = relativePath(tenantId, ticketId, "quarantine", storedName);
        StoredAttachmentObject storedObject;
        try {
            storedObject = storageService.put(objectKey, content, verifiedContentType,
                    Map.of("tenant-id", String.valueOf(tenantId), "ticket-id", String.valueOf(ticketId),
                            "uploader-id", String.valueOf(uploaderId)));
        } catch (AttachmentStorageException ex) {
            log.warn("Attachment upload to {} storage failed", storageService.configuredType(), ex);
            throw new BusinessException("附件保存失败，请稍后重试");
        }

        TicketAttachment attachment = new TicketAttachment();
        attachment.setTenantId(tenantId);
        attachment.setTicketId(ticketId);
        attachment.setUploaderId(uploaderId);
        attachment.setOriginalName(originalName);
        attachment.setStoredName(storedName);
        attachment.setContentType(verifiedContentType);
        attachment.setSizeBytes(file.getSize());
        attachment.setStoragePath(objectKey);
        attachment.setStorageBackend(storageService.configuredType());
        attachment.setStorageEtag(storedObject.etag());
        attachment.setStorageVersion(storedObject.versionId());
        attachment.setScanStatus(AttachmentScanStatus.PENDING);
        attachment.setScanAttempts(0);
        LocalDateTime now = LocalDateTime.now();
        attachment.setNextScanAt(now);
        attachment.setQuarantinedAt(now);
        attachment.setCreatedAt(now);
        attachment.setUpdatedAt(now);
        try {
            attachmentMapper.insert(attachment);
            auditLogService.record(tenantId, uploaderId, "TICKET", ticketId, "ATTACHMENT_ADDED",
                    null, java.util.Map.of("attachmentId", attachment.getId(), "name", originalName,
                            "scanStatus", AttachmentScanStatus.PENDING,
                            "storageBackend", attachment.getStorageBackend()),
                    "上传附件并进入安全隔离区");
            return attachment;
        } catch (RuntimeException ex) {
            try {
                storageService.delete(attachment);
            } catch (RuntimeException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
        }
    }

    public DownloadedAttachment download(Long ticketId, Long attachmentId, Long tenantId, Long requesterId) {
        ticketService.getVisible(ticketId, tenantId, requesterId);
        TicketAttachment attachment = find(ticketId, attachmentId, tenantId);
        requireDownloadable(attachment);
        try {
            return new DownloadedAttachment(attachment, storageService.load(attachment));
        } catch (AttachmentStorageException ex) {
            log.warn("Attachment {} cannot be read from {}", attachment.getId(), attachment.getStorageBackend(), ex);
            throw new BusinessException("附件读取失败");
        }
    }

    public DownloadAccess downloadAccess(Long ticketId, Long attachmentId, Long tenantId, Long requesterId) {
        ticketService.getVisible(ticketId, tenantId, requesterId);
        TicketAttachment attachment = find(ticketId, attachmentId, tenantId);
        requireDownloadable(attachment);
        return storageService.presignDownload(attachment)
                .map(signed -> new DownloadAccess(true, signed.uri().toString(), signed.expiresAt()))
                .orElseGet(() -> new DownloadAccess(false, null, null));
    }

    @Transactional
    public void delete(Long ticketId, Long attachmentId, Long actorId, Long tenantId,
                       boolean staff, Long requesterId) {
        ticketService.getVisible(ticketId, tenantId, requesterId);
        TicketAttachment attachment = findForUpdate(ticketId, attachmentId, tenantId);
        if (!staff && !actorId.equals(attachment.getUploaderId())) {
            throw new BusinessException("只能删除自己上传的附件");
        }
        attachmentMapper.deleteById(attachment.getId());
        auditLogService.record(tenantId, actorId, "TICKET", ticketId, "ATTACHMENT_DELETED",
                java.util.Map.of("attachmentId", attachment.getId(), "name", attachment.getOriginalName()),
                null, "删除附件");
        deleteAfterCommit(attachment);
    }

    private TicketAttachment find(Long ticketId, Long attachmentId, Long tenantId) {
        TicketAttachment attachment = attachmentMapper.selectOne(new LambdaQueryWrapper<TicketAttachment>()
                .eq(TicketAttachment::getId, attachmentId)
                .eq(TicketAttachment::getTicketId, ticketId)
                .eq(TicketAttachment::getTenantId, tenantId));
        if (attachment == null) throw new BusinessException("附件不存在");
        return attachment;
    }

    private TicketAttachment findForUpdate(Long ticketId, Long attachmentId, Long tenantId) {
        TicketAttachment attachment = attachmentMapper.selectForUpdate(attachmentId, tenantId);
        if (attachment == null || !ticketId.equals(attachment.getTicketId())) {
            throw new BusinessException("附件不存在");
        }
        return attachment;
    }

    private String relativePath(Long tenantId, Long ticketId, String area, String storedName) {
        return area + "/" + tenantId + "/" + ticketId + "/" + storedName;
    }

    private String downloadBlockedMessage(AttachmentScanStatus status) {
        if (status == AttachmentScanStatus.INFECTED) return "附件检测到安全威胁，已被隔离";
        if (status == AttachmentScanStatus.FAILED) return "附件安全扫描失败，等待管理员处理";
        return "附件正在安全扫描，暂不可下载";
    }

    private byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException("附件内容读取失败");
        }
    }

    private void requireDownloadable(TicketAttachment attachment) {
        if (attachment.getScanStatus() != AttachmentScanStatus.CLEAN
                && attachment.getScanStatus() != AttachmentScanStatus.RELEASED) {
            throw new AttachmentDownloadBlockedException(downloadBlockedMessage(attachment.getScanStatus()));
        }
    }

    private void deleteAfterCommit(TicketAttachment attachment) {
        Runnable cleanup = () -> {
            try {
                storageService.delete(attachment);
            } catch (RuntimeException ex) {
                log.error("Attachment {} database record was deleted but object cleanup failed", attachment.getId(), ex);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    public record DownloadedAttachment(TicketAttachment attachment, Resource resource) { }
    public record DownloadAccess(boolean direct, String url, Instant expiresAt) { }
}
