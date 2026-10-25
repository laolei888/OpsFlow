package com.opsflow.chaos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chaos_experiment_checks")
public class ChaosExperimentCheck {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long experimentId;
    private ChaosCheckPhase phase;
    private String checkKey;
    private String label;
    private ChaosCheckStatus status;
    private Boolean requiredCheck;
    private String detail;
    private String evidence;
    private Integer sortOrder;
    private Long updatedBy;
    private LocalDateTime checkedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
