package com.opsflow.recovery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("recovery_drills")
public class RecoveryDrill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String drillNo;
    private String name;
    private Long backupSnapshotId;
    private RecoveryDrillStatus status;
    @Version
    private Integer version;
    private String environment;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer targetRpoMinutes;
    private Integer targetRtoMinutes;
    private Integer achievedRpoMinutes;
    private Integer achievedRtoMinutes;
    private Integer integrityScore;
    private String evidenceNote;
    private String findings;
    private String failureReason;
    private Long createdBy;
    private Long startedBy;
    private Long completedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
