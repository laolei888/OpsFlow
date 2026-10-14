package com.opsflow.webhook;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("webhook_deliveries")
public class WebhookDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long endpointId;
    private String eventId;
    private String eventType;
    private String resourceType;
    private Long resourceId;
    private String payloadJson;
    private String signature;
    private WebhookDeliveryStatus status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private Integer responseStatus;
    private String lastError;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
