package com.opsflow.automation;

import com.opsflow.ticket.TicketPriority;
import com.opsflow.ticket.TicketStatus;

import java.time.LocalDateTime;

public record AutomationRuleView(Long id, Long tenantId, String name, Boolean enabled,
                                 Integer sortOrder, String matchCategory,
                                 TicketPriority matchPriority, String matchKeyword,
                                 Long targetAssigneeId, Long targetGroupId,
                                 AutomationAssignmentStrategy assignmentStrategy,
                                 TicketPriority targetPriority,
                                 TicketStatus targetStatus, Long createdBy, Long updatedBy,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) { }
