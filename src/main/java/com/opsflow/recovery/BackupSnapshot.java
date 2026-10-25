package com.opsflow.recovery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("backup_snapshots")
public class BackupSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String backupNo;
    private BackupScope scope;
    private BackupStatus status;
    private String storageProvider;
    private String storageLocation;
    private String region;
    private LocalDateTime recoveryPointAt;
    private LocalDateTime capturedAt;
    private LocalDateTime completedAt;
    private Long sizeBytes;
    private String checksumSha256;
    private Boolean encrypted;
    private LocalDateTime immutableUntil;
    private Boolean crossRegionCopy;
    private BackupVerificationStatus verificationStatus;
    private String verificationDetail;
    private LocalDateTime verifiedAt;
    private String evidenceNote;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
