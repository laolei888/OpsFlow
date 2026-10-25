package com.opsflow.recovery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("disaster_recovery_policies")
public class DisasterRecoveryPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Integer rpoMinutes;
    private Integer rtoMinutes;
    private Integer retentionDays;
    private Integer verificationIntervalHours;
    private Boolean requireEncryption;
    private Boolean requireImmutableCopy;
    private Boolean requireCrossRegionCopy;
    private Boolean enabled;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
