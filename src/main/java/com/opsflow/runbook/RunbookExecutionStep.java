package com.opsflow.runbook;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runbook_execution_steps")
public class RunbookExecutionStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long executionId;
    private Long runbookStepId;
    private Integer sortOrder;
    private String title;
    private String instruction;
    private String verificationHint;
    private Boolean requiredStep;
    private RunbookStepStatus status;
    private String evidence;
    private Long completedBy;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
