package com.opsflow.attachment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("attachment_upload_sessions")
public class AttachmentUploadSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionKey;
    private String clientUploadId;
    private Long tenantId;
    private Long ticketId;
    private Long uploaderId;
    private String originalName;
    private String storedName;
    private String contentType;
    private Long sizeBytes;
    private AttachmentStorageType storageBackend;
    @JsonIgnore
    private String storagePath;
    @JsonIgnore
    private String providerUploadId;
    private AttachmentUploadStatus status;
    private Long partSize;
    private Integer totalParts;
    private Long attachmentId;
    private String failureReason;
    private LocalDateTime expiresAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
