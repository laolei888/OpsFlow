package com.opsflow.recovery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("backup_agent_callbacks")
public class BackupAgentCallback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long agentId;
    private String eventId;
    private String nonce;
    private String payloadSha256;
    private Long backupSnapshotId;
    private LocalDateTime receivedAt;
}
