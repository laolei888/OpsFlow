package com.opsflow.slo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("slo_measurements")
public class SloMeasurement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long definitionId;
    private SloMeasurementSource source;
    private Long sourceAlertId;
    private Long totalRequests;
    private Long goodRequests;
    private BigDecimal availabilityPercent;
    private String note;
    private LocalDateTime occurredAt;
    private Long recordedBy;
    private LocalDateTime createdAt;
}
