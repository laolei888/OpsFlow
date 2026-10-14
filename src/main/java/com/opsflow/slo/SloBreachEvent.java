package com.opsflow.slo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("slo_breach_events")
public class SloBreachEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String breachNo;
    private Long definitionId;
    private Long sourceMeasurementId;
    private SloBreachLevel level;
    private SloBreachStatus status;
    private BigDecimal availabilityPercent;
    private BigDecimal budgetRemainingPercent;
    private String title;
    private Long linkedTicketId;
    private Long linkedIncidentId;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
