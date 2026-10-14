package com.opsflow.runbook;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.opsflow.alert.AlertSeverity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runbook_alert_matchers")
public class RunbookAlertMatcher {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long runbookId;
    private String name;
    private Long alertSourceId;
    private AlertSeverity minimumSeverity;
    private String titleKeyword;
    private Integer sortOrder;
    private Boolean enabled;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
