package com.opsflow.attachment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("attachment_storage_findings")
public class AttachmentStorageFinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonIgnore
    private String findingFingerprint;
    private Long tenantId;
    private AttachmentStorageType storageBackend;
    private AttachmentStorageFindingType findingType;
    private AttachmentStorageFindingStatus status;
    private String objectKey;
    private String versionId;
    private Long sizeBytes;
    private LocalDateTime objectLastModifiedAt;
    private Boolean latestVersion;
    private LocalDateTime eligibleDeleteAt;
    private LocalDateTime detectedAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime resolvedAt;
    private Long handledBy;
    private String handlingReason;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
