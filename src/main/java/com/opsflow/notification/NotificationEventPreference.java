package com.opsflow.notification;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification_event_preferences")
public class NotificationEventPreference {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private String eventType;
    private Boolean inAppEnabled;
    private Boolean emailEnabled;
    private Boolean webhookEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
