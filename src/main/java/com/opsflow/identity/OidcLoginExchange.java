package com.opsflow.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oidc_login_exchanges")
public class OidcLoginExchange {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String codeHash;
    private Long tenantId;
    private Long userId;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;
}
