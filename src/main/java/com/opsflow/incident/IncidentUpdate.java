package com.opsflow.incident;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("incident_updates")
public class IncidentUpdate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long incidentId;
    private IncidentUpdateType updateType;
    private String content;
    private Long createdBy;
    private LocalDateTime createdAt;
}
