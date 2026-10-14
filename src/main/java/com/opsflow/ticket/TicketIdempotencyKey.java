package com.opsflow.ticket;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_idempotency_keys")
public class TicketIdempotencyKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long requesterId;
    private String idempotencyKey;
    private String requestHash;
    private Long ticketId;
    private LocalDateTime createdAt;
}
