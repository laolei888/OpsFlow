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
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(TicketAttachmentService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "pdf", "txt", "md", "csv",
            "doc", "docx", "xls", "xlsx", "zip", "log");
    private static final Map<String, String> CANONICAL_CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"), Map.entry("csv", "text/csv"),
            Map.entry("log", "text/plain"), Map.entry("zip", "application/zip"),
            Map.entry("doc", "application/msword"), Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

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

        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("不支持的附件类型，请上传图片、文档、表格、文本或压缩包");
        }
        byte[] content = readContent(file);
        String verifiedContentType = verifyContent(content, extension);

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

    private String safeOriginalName(String value) {
        if (!StringUtils.hasText(value)) throw new BusinessException("附件文件名不能为空");
        String normalized;
        try {
            normalized = StringUtils.cleanPath(value).replace("\\", "/");
        } catch (InvalidPathException ex) {
            throw new BusinessException("附件文件名非法");
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (!StringUtils.hasText(name) || name.equals(".") || name.equals("..") || name.contains("\u0000")) {
            throw new BusinessException("附件文件名非法");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private byte[] readContent(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException("附件内容读取失败");
        }
    }

    private String verifyContent(byte[] content, String extension) {
        boolean valid = switch (extension) {
            case "png" -> startsWith(content, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "jpg", "jpeg" -> startsWith(content, 0xff, 0xd8, 0xff);
            case "gif" -> startsWithAscii(content, "GIF87a") || startsWithAscii(content, "GIF89a");
            case "pdf" -> startsWithAscii(content, "%PDF-");
            case "zip", "docx", "xlsx" -> isZip(content);
            case "doc", "xls" -> startsWith(content, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1);
            case "txt", "md", "csv", "log" -> isUtf8Text(content);
            default -> false;
        };
        if (!valid) {
            throw new BusinessException("附件实际内容与文件扩展名不匹配");
        }
        return CANONICAL_CONTENT_TYPES.get(extension);
    }

    private boolean isZip(byte[] content) {
        return startsWith(content, 0x50, 0x4b, 0x03, 0x04)
                || startsWith(content, 0x50, 0x4b, 0x05, 0x06)
                || startsWith(content, 0x50, 0x4b, 0x07, 0x08);
    }

    private boolean isUtf8Text(byte[] content) {
        for (byte item : content) {
            if (item == 0) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private boolean startsWithAscii(byte[] content, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.US_ASCII);
        if (content.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (content[i] != expected[i]) return false;
        }
        return true;
    }

    private boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xff) != signature[i]) return false;
        }
        return true;
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
