package com.opsflow.capacity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capacity_policies")
public class CapacityPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long activeTicketsThreshold;
    private Long overdueTicketsThreshold;
    private Long activeIncidentsThreshold;
    private Long firingAlertsThreshold;
    private Long asyncBacklogThreshold;
    private Long attachmentBacklogThreshold;
    private Long attachmentBytesThreshold;
    private Long databaseBytesThreshold;
    private Integer warningPercent;
    private Integer watchDays;
    private Boolean enabled;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
