package com.opsflow.problem;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("problem_ticket_links")
public class ProblemTicketLink {
    @TableId
    private Long problemId;
    private Long tenantId;
    private Long ticketId;
    private String linkType;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
