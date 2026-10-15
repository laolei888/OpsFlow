package com.opsflow.messaging;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("async_message_outbox")
public class AsyncMessageOutboxEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long recipientId;
    private String messageId;
    private String eventType;
    private String routingKey;
    private String aggregateType;
    private Long aggregateId;
    private String dedupeKey;
    private String payloadJson;
    private AsyncMessageOutboxStatus status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime claimedAt;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
