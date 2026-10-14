package com.opsflow.dashboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dashboard_report_subscriptions")
public class DashboardReportSubscription {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long ownerId;
    private String name;
    private DashboardReportFrequency frequency;
    private Integer rangeDays;
    private Boolean enabled;
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}