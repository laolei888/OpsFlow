package com.opsflow.notification;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notifications")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long recipientId;
    private Long ticketId;
    private String type;
    private String title;
    private String content;
    private String dedupeKey;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}