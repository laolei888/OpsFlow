package com.opsflow.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oidc_provider_mappings")
public class OidcProviderMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String registrationId;
    private String displayName;
    private Boolean enabled;
    private Boolean autoProvision;
    private String defaultRole;
    private String allowedEmailDomains;
    private String requiredGroup;
    private String usernameClaim;
    private String displayNameClaim;
    private String emailClaim;
    private String groupsClaim;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
