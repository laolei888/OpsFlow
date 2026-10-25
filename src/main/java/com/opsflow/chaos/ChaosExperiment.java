package com.opsflow.chaos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chaos_experiments")
public class ChaosExperiment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String experimentNo;
    private String name;
    private ChaosTarget target;
    private String environment;
    private ChaosExperimentStatus status;
    @Version
    private Integer version;
    private String hypothesis;
    private String rollbackPlan;
    private Integer targetRecoveryMinutes;
    private LocalDateTime startedAt;
    private LocalDateTime faultStartedAt;
    private LocalDateTime recoveryStartedAt;
    private LocalDateTime completedAt;
    private Long achievedRecoverySeconds;
    private String findings;
    private String cancellationReason;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
