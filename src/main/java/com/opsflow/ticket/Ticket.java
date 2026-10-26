package com.opsflow.ticket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tickets")
public class Ticket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private Long tenantId;
    private Long requesterId;
    private Long assigneeId;
    private Long assignmentGroupId;
    private String title;
    private String description;
    private String impactScope;
    private String expectedOutcome;
    private String attemptedActions;
    private String category;
    private TicketPriority priority;
    private TicketStatus status;
    @Version
    private Integer version;
    private LocalDateTime dueAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
