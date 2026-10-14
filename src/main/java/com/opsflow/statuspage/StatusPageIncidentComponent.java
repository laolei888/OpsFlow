package com.opsflow.statuspage;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("status_page_incident_components")
public class StatusPageIncidentComponent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long statusIncidentId;
    private Long componentId;
    private LocalDateTime createdAt;
}
