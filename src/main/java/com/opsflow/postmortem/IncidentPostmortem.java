package com.opsflow.postmortem;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("incident_postmortems")
public class IncidentPostmortem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long incidentId;
    private String reviewNo;
    private String title;
    private PostmortemStatus status;
    private Long facilitatorId;
    private Long reviewerId;
    private String executiveSummary;
    private String customerImpact;
    private String rootCause;
    private String contributingFactors;
    private String detectionGap;
    private String resolutionSummary;
    private String lessonsLearned;
    private String preventionPlan;
    private LocalDateTime dueAt;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime publishedAt;
    private String reviewComment;
    private String rejectedReason;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
