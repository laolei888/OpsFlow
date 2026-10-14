package com.opsflow.ticket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_comments")
public class TicketComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long ticketId;
    private Long authorId;
    private String content;
    private TicketCommentVisibility visibility;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}