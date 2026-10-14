package com.opsflow.incident;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("incidents")
public class Incident {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String incidentNo;
    private String title;
    private String summary;
    private IncidentSeverity severity;
    private IncidentStatus status;
    private Long commanderId;
    private Long declaredBy;
    private String businessImpact;
    private String customerImpact;
    private String communicationChannel;
    private String postmortem;
    private String requestKey;
    private String requestHash;
    private LocalDateTime startedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
