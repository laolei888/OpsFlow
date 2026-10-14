package com.opsflow.supportgroup;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("support_groups")
public class SupportGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String groupCode;
    private String name;
    private String description;
    private Long managerId;
    private Boolean enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
