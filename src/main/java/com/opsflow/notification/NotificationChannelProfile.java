package com.opsflow.notification;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("notification_channel_profiles")
public class NotificationChannelProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private String emailAddress;
    private String webhookUrl;
    private String webhookSecret;
    private String wecomWebhookUrl;
    private String preferredLocale;
    private String timeZone;
    private Boolean deliveryWindowEnabled;
    private LocalTime deliveryWindowStart;
    private LocalTime deliveryWindowEnd;
    private Boolean urgentBypass;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
