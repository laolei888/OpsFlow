package com.opsflow.runbook;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runbook_steps")
public class RunbookStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long runbookId;
    private Integer sortOrder;
    private String title;
    private String instruction;
    private String verificationHint;
    private Boolean requiredStep;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
