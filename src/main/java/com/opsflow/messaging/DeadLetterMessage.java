package com.opsflow.messaging;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("async_dead_letter_messages")
public class DeadLetterMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long outboxEventId;
    private String messageId;
    private String notificationType;
    private String title;
    private Long recipientId;
    private String dedupeKey;
    private String sourceQueue;
    private String sourceExchange;
    private String originalRoutingKey;
    private String deadLetterReason;
    private Long deathCount;
    private String payloadJson;
    private String headersJson;
    private DeadLetterStatus status;
    private Integer requeueCount;
    private String lastError;
    private String resolutionNote;
    private LocalDateTime quarantinedAt;
    private LocalDateTime requeueStartedAt;
    private LocalDateTime lastRequeuedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime updatedAt;
}
