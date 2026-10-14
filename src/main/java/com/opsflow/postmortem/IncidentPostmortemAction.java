package com.opsflow.postmortem;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("incident_postmortem_actions")
public class IncidentPostmortemAction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long postmortemId;
    private String title;
    private String description;
    private Long ownerId;
    private PostmortemActionPriority priority;
    private PostmortemActionStatus status;
    private LocalDateTime dueAt;
    private String completionEvidence;
    private LocalDateTime completedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
