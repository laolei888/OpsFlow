package com.opsflow.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthSource;
import com.opsflow.auth.RoleMapper;
import com.opsflow.auth.User;
import com.opsflow.auth.UserMapper;
import com.opsflow.auth.UserRole;
import com.opsflow.auth.UserRoleMapper;
import com.opsflow.auth.UserStatus;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OidcAccountService {
    private final OidcProviderService providerService;
    private final UserExternalIdentityMapper identityMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuditLogService auditLogService;

    @Transactional
    public User authenticate(String registrationId, Map<String, Object> claims) {
        OidcProviderMapping provider = providerService.requireEnabled(registrationId);
        String subject = claim(claims, "sub");
        if (subject == null || subject.length() > 191) throw new BusinessException("OIDC subject 无效");
        String email = claim(claims, provider.getEmailClaim());
        validateEmailDomain(provider, email);
        validateRequiredGroup(provider, claims.get(provider.getGroupsClaim()));

        LocalDateTime now = LocalDateTime.now();
        UserExternalIdentity identity = identityMapper.selectByProviderAndSubject(provider.getId(), subject);
        User user;
        if (identity != null) {
            user = userMapper.selectById(identity.getUserId());
            if (user == null || !provider.getTenantId().equals(user.getTenantId())) {
                throw new BusinessException("企业身份映射异常，请联系管理员");
            }
            if (user.getStatus() != UserStatus.ACTIVE) throw new BusinessException("账号已停用");
            String issuer = claim(claims, "iss");
            if (identity.getIssuer() != null && issuer != null && !identity.getIssuer().equals(issuer)) {
                throw new BusinessException("企业身份签发方与已绑定账号不一致");
            }
            if (identity.getIssuer() == null) identity.setIssuer(issuer);
            identity.setEmail(email);
            identity.setLastLoginAt(now);
            identityMapper.updateById(identity);
        } else {
            if (!Boolean.TRUE.equals(provider.getAutoProvision())) {
                throw new BusinessException("当前身份尚未绑定 OpsFlow 账号，请联系管理员");
            }
            user = provision(provider, subject, email, claims, now);
            identity = new UserExternalIdentity();
            identity.setTenantId(provider.getTenantId());
            identity.setUserId(user.getId());
            identity.setProviderId(provider.getId());
            identity.setIssuer(claim(claims, "iss"));
            identity.setSubject(subject);
            identity.setEmail(email);
            identity.setLinkedAt(now);
            identity.setLastLoginAt(now);
            identityMapper.insert(identity);
            auditLogService.record(provider.getTenantId(), user.getId(), "USER", user.getId(),
                    "OIDC_USER_PROVISIONED", null, null, "企业身份首次登录并自动创建账号");
        }
        user.setEmail(email);
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        userMapper.updateById(user);
        auditLogService.record(provider.getTenantId(), user.getId(), "USER", user.getId(),
                "OIDC_LOGIN_SUCCEEDED", null, null, "企业单点登录成功: " + provider.getRegistrationId());
        return user;
    }

    private User provision(OidcProviderMapping provider, String subject, String email,
                           Map<String, Object> claims, LocalDateTime now) {
        String preferred = claim(claims, provider.getUsernameClaim());
        if (preferred == null && email != null && email.contains("@")) {
            preferred = email.substring(0, email.indexOf('@'));
        }
        String username = uniqueUsername(preferred, subject);
        String displayName = claim(claims, provider.getDisplayNameClaim());
        if (displayName == null) displayName = username;
        if (displayName.length() > 64) displayName = displayName.substring(0, 64);

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(null);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setAuthSource(AuthSource.OIDC);
        user.setTenantId(provider.getTenantId());
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        user.setLastLoginAt(now);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        Long roleId = roleMapper.selectIdByCode(provider.getDefaultRole());
        if (roleId == null) throw new BusinessException("OIDC 默认角色不存在");
        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);
        return user;
    }

    private void validateEmailDomain(OidcProviderMapping provider, String email) {
        if (provider.getAllowedEmailDomains() == null || provider.getAllowedEmailDomains().isBlank()) return;
        if (email == null || !email.contains("@")) throw new BusinessException("身份提供商未返回受信任邮箱");
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        boolean allowed = List.of(provider.getAllowedEmailDomains().split(",")).stream()
                .map(String::trim).anyMatch(domain::equals);
        if (!allowed) throw new BusinessException("邮箱域名不在企业身份白名单中");
    }

    private void validateRequiredGroup(OidcProviderMapping provider, Object rawGroups) {
        if (provider.getRequiredGroup() == null || provider.getRequiredGroup().isBlank()) return;
        boolean matches = rawGroups instanceof Collection<?> collection
                ? collection.stream().map(String::valueOf).anyMatch(provider.getRequiredGroup()::equals)
                : rawGroups != null && List.of(String.valueOf(rawGroups).split("[, ]"))
                        .contains(provider.getRequiredGroup());
        if (!matches) throw new BusinessException("当前企业身份不属于允许登录的用户组");
    }

    private String uniqueUsername(String preferred, String subject) {
        String base = preferred == null ? "" : preferred.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-").replaceAll("-+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (base.length() < 4) base = "oidc-" + shortHash(subject);
        if (base.length() > 52) base = base.substring(0, 52);
        String candidate = base;
        int suffix = 1;
        while (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, candidate)) != null) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String claim(Map<String, Object> claims, String name) {
        Object value = name == null ? null : claims.get(name);
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }
}
