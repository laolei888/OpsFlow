package com.opsflow.oncall;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.opsflow.incident.IncidentSeverity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oncall_escalation_events")
public class OnCallEscalationEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long incidentId;
    private Long policyId;
    private String incidentNo;
    private String incidentTitle;
    private IncidentSeverity severity;
    private OnCallEscalationEventStatus status;
    private Integer currentStep;
    private LocalDateTime nextEscalateAt;
    private LocalDateTime lastNotifiedAt;
    private String lastError;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
