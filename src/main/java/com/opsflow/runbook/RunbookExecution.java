package com.opsflow.runbook;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runbook_executions")
public class RunbookExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String executionNo;
    private Long runbookId;
    private Integer runbookVersion;
    private RunbookExecutionSource sourceType;
    private Long sourceId;
    private String sourceRef;
    private String title;
    private RunbookExecutionStatus status;
    private Long initiatedBy;
    private Long completedBy;
    private String outcomeNote;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
