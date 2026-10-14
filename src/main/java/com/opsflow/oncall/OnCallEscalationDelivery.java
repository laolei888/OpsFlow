package com.opsflow.oncall;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oncall_escalation_deliveries")
public class OnCallEscalationDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long escalationEventId;
    private Integer stepNo;
    private Long recipientId;
    private OnCallEscalationTargetType targetType;
    private OnCallEscalationDeliveryStatus status;
    private String detail;
    private LocalDateTime createdAt;
}
