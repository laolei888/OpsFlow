package com.opsflow.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_logs")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long actorId;
    private String resourceType;
    private Long resourceId;
    private String action;
    private String beforeValue;
    private String afterValue;
    private String detail;
    private LocalDateTime createdAt;
}