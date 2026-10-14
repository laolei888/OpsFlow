package com.opsflow.webhook;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("webhook_outbox_events")
public class WebhookOutboxEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long auditLogId;
    private String eventId;
    private String eventType;
    private Long actorId;
    private String resourceType;
    private Long resourceId;
    private String payloadJson;
    private WebhookOutboxStatus status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime claimedAt;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
