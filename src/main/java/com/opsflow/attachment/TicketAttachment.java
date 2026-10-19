package com.opsflow.attachment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_attachments")
public class TicketAttachment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long ticketId;
    private Long uploaderId;
    private String originalName;
    private String storedName;
    private String contentType;
    private Long sizeBytes;
    @JsonIgnore
    private String storagePath;
    private AttachmentScanStatus scanStatus;
    private String scanEngine;
    private String scanResult;
    private String sha256;
    private Integer scanAttempts;
    private LocalDateTime nextScanAt;
    @JsonIgnore
    private String scanClaimToken;
    private LocalDateTime scanClaimedAt;
    private LocalDateTime scannedAt;
    private LocalDateTime quarantinedAt;
    private Long releasedBy;
    private LocalDateTime releasedAt;
    private String releaseReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private String downloadUrl;
}
