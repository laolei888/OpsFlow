package com.opsflow.capacity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capacity_snapshots")
public class CapacitySnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private LocalDateTime capturedAt;
    private CapacityCaptureType captureType;
    private String label;
    private String appVersion;
    private Long releaseId;
    private Long activeUsers;
    private Long totalTickets;
    private Long activeTickets;
    private Long overdueTickets;
    private Long ticketsCreated24h;
    private Long ticketsResolved24h;
    private Long publishedArticles;
    private Long activeAssets;
    private Long activeIncidents;
    private Long firingAlerts;
    private Long asyncBacklog;
    private Long attachmentBacklog;
    private Long attachmentBytes;
    private Long databaseBytes;
    private Long heapUsedBytes;
    private Long heapMaxBytes;
    private Long createdBy;
    private LocalDateTime createdAt;
}
