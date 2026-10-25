package com.opsflow.recovery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("recovery_drill_checks")
public class RecoveryDrillCheck {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long drillId;
    private String checkKey;
    private String label;
    private RecoveryCheckStatus status;
    private Boolean requiredCheck;
    private String detail;
    private String evidence;
    private Integer sortOrder;
    private Long updatedBy;
    private LocalDateTime checkedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
