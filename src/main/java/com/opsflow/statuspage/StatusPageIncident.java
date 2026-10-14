package com.opsflow.statuspage;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("status_page_incidents")
public class StatusPageIncident {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String publicNo;
    private Long sourceIncidentId;
    private String title;
    private String summary;
    private StatusPageIncidentImpact impact;
    private StatusPageIncidentStatus status;
    private Boolean published;
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
