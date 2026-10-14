package com.opsflow.release;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("release_plans")
public class ReleasePlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String releaseNo;
    private String name;
    private String description;
    private ReleaseEnvironment environment;
    private Long releaseManagerId;
    private LocalDateTime plannedStart;
    private LocalDateTime plannedEnd;
    private ReleaseStatus status;
    private ReleaseGateDecision goNoGoStatus;
    private String decisionComment;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private String outcomeNote;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
