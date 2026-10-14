package com.opsflow.change;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("change_requests")
public class ChangeRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String changeNo;
    private Long requesterId;
    private String title;
    private String description;
    private ChangeType changeType;
    private ChangeRisk riskLevel;
    private String impactScope;
    private String implementationPlan;
    private String validationPlan;
    private String rollbackPlan;
    private LocalDateTime scheduledStart;
    private LocalDateTime scheduledEnd;
    private ChangeStatus status;
    private Long linkedTicketId;
    private String requestKey;
    private String requestHash;
    private String decisionComment;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
