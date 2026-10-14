package com.opsflow.satisfaction;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_satisfaction_responses")
public class TicketSatisfactionResponse {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long ticketId;
    private Long requesterId;
    private Long assigneeId;
    private Long assignmentGroupId;
    private Integer rating;
    private String tags;
    private String comment;
    private LocalDateTime submittedAt;
}
