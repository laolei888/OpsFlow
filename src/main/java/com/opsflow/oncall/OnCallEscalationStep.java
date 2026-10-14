package com.opsflow.oncall;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oncall_escalation_steps")
public class OnCallEscalationStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long policyId;
    private Integer stepNo;
    private Integer delayMinutes;
    private OnCallEscalationTargetType targetType;
    private Long scheduleId;
    private Long targetUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
