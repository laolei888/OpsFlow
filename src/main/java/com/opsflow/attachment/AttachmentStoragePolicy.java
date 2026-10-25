package com.opsflow.attachment;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("attachment_storage_policies")
public class AttachmentStoragePolicy {
    @TableId
    private Long tenantId;
    private Long quotaBytes;
    private Integer warningPercent;
    private LocalDateTime lastReconciledAt;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
