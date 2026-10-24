package com.opsflow.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_external_identities")
public class UserExternalIdentity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long providerId;
    private String issuer;
    private String subject;
    private String email;
    private LocalDateTime linkedAt;
    private LocalDateTime lastLoginAt;
}
