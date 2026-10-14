package com.opsflow.supportgroup;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("support_group_members")
public class SupportGroupMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long groupId;
    private Long userId;
    private SupportGroupMemberRole memberRole;
    private Boolean active;
    private LocalDateTime joinedAt;
    private Long addedBy;
}
