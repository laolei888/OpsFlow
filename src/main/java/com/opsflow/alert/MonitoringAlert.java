package com.opsflow.alert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("monitoring_alerts")
public class MonitoringAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String alertNo;
    private Long tenantId;
    private Long sourceId;
    private Long ruleId;
    private String fingerprint;
    private String externalAlertId;
    private String title;
    private String description;
    private AlertSeverity severity;
    private AlertStatus status;
    private Long assetId;
    private String labelsJson;
    private Integer occurrenceCount;
    private Integer generation;
    private Long ticketId;
    private Long incidentId;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
