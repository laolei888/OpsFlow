package com.opsflow.ticket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_saved_views")
public class TicketSavedView {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long ownerId;
    private String name;
    private String visibility;
    private TicketStatus status;
    private TicketPriority priority;
    private TicketSort sort;
    private String keyword;
    private Boolean overdue;
    private Boolean mine;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}