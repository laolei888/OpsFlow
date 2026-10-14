package com.opsflow.slo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("slo_definitions")
public class SloDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private Long statusComponentId;
    private BigDecimal targetPercent;
    private Integer windowDays;
    private BigDecimal warningBudgetPercent;
    private BigDecimal criticalBudgetPercent;
    private Boolean enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
