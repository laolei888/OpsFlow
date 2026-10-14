package com.opsflow.incident;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("incident_ticket_links")
public class IncidentTicketLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long incidentId;
    private Long ticketId;
    private String linkType;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
