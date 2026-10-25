package com.opsflow.recovery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("backup_agents")
public class BackupAgent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String agentKey;
    private String name;
    private String description;
    @JsonIgnore
    private String secretCiphertext;
    private Boolean enabled;
    private LocalDateTime lastSeenAt;
    private String lastEventId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
