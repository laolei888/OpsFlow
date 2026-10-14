package com.opsflow.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.opsflow.ticket.TicketPriority;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_requests")
public class ServiceRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private Long tenantId;
    private Long catalogItemId;
    private Long requesterId;
    private Long ticketId;
    @JsonIgnore
    private String requestKey;
    @JsonIgnore
    private String requestHash;
    private String itemName;
    private String category;
    private TicketPriority priority;
    private String summary;
    private String detail;
    private ServiceRequestStatus status;
    private String decisionComment;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
