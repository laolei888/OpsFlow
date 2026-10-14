package com.opsflow.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_automation_rules")
public class AutomationRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private Boolean enabled;
    private Integer sortOrder;
    private String matchCategory;
    private TicketPriority matchPriority;
    private String matchKeyword;
    private Long targetAssigneeId;
    private Long targetGroupId;
    private AutomationAssignmentStrategy assignmentStrategy;
    private TicketPriority targetPriority;
    private TicketStatus targetStatus;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
