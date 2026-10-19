package com.opsflow.notification;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification_deliveries")
public class NotificationDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long notificationId;
    private Long recipientId;
    private String notificationType;
    private NotificationChannel channel;
    private String destination;
    private String deliverySecret;
    private NotificationDeliveryStatus status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private Integer responseStatus;
    private String lastError;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
