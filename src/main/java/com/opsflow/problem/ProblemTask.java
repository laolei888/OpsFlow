package com.opsflow.problem;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("problem_tasks")
public class ProblemTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long problemId;
    private String title;
    private Long assigneeId;
    private Integer sortOrder;
    private ProblemTaskStatus status;
    private LocalDateTime completedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
