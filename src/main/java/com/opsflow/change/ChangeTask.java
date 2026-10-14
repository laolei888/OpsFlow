package com.opsflow.change;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("change_tasks")
public class ChangeTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long changeId;
    private String title;
    private Long assigneeId;
    private Integer sortOrder;
    private ChangeTaskStatus status;
    private LocalDateTime completedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
