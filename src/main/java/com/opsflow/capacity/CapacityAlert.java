package com.opsflow.capacity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("capacity_alerts")
public class CapacityAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String alertNo;
    private String metricKey;
    private String metricLabel;
    private CapacityAlertLevel level;
    private CapacityAlertStatus status;
    private Long currentValue;
    private Long thresholdValue;
    private BigDecimal utilizationPercent;
    private BigDecimal dailyGrowth;
    private Long projectedDays;
    private Long snapshotId;
    private Long policyId;
    private Integer occurrenceCount;
    private Boolean notificationSuppressed;
    private Integer suppressedNotificationCount;
    private Long suppressedByWindowId;
    private String suppressedReason;
    private LocalDateTime lastSuppressedAt;
    private LocalDateTime firstDetectedAt;
    private LocalDateTime lastDetectedAt;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private String acknowledgementNote;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolutionNote;
    private String activeKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
