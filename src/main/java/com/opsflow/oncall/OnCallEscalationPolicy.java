package com.opsflow.oncall;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.opsflow.incident.IncidentSeverity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oncall_escalation_policies")
public class OnCallEscalationPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private IncidentSeverity minimumSeverity;
    private Boolean enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
