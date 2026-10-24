package com.opsflow.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.RoleMapper;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OidcProviderService {
    private static final Pattern REGISTRATION_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final Pattern CLAIM_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Set<String> ROLES = Set.of("ADMIN", "AGENT", "REQUESTER");

    private final OidcProviderMappingMapper mapper;
    private final RoleMapper roleMapper;
    private final AuditLogService auditLogService;
    private final ObjectProvider<ClientRegistrationRepository> registrationRepositories;

    public List<OidcProviderView> list(Long tenantId) {
        return mapper.selectList(new LambdaQueryWrapper<OidcProviderMapping>()
                        .eq(OidcProviderMapping::getTenantId, tenantId)
                        .orderByAsc(OidcProviderMapping::getDisplayName))
                .stream().map(this::toView).toList();
    }

    public List<PublicOidcProvider> publicProviders() {
        return mapper.selectList(new LambdaQueryWrapper<OidcProviderMapping>()
                        .eq(OidcProviderMapping::getEnabled, true)
                        .orderByAsc(OidcProviderMapping::getDisplayName))
                .stream()
                .filter(item -> clientRegistration(item.getRegistrationId()) != null)
                .map(item -> new PublicOidcProvider(item.getRegistrationId(), item.getDisplayName(),
                        "/oauth2/authorization/" + item.getRegistrationId()))
                .toList();
    }

    public OidcProviderMapping requireEnabled(String registrationId) {
        OidcProviderMapping mapping = mapper.selectByRegistrationId(registrationId);
        if (mapping == null || !Boolean.TRUE.equals(mapping.getEnabled())) {
            throw new BusinessException("企业身份提供商未启用");
        }
        if (clientRegistration(registrationId) == null) {
            throw new BusinessException("企业身份提供商尚未完成服务端凭据配置");
        }
        return mapping;
    }

    @Transactional
    public OidcProviderView create(Long tenantId, Long actorId, OidcProviderCommand command) {
        String registrationId = normalizeRegistrationId(command.registrationId());
        if (mapper.selectByRegistrationId(registrationId) != null) {
            throw new BusinessException("registrationId 已存在");
        }
        OidcProviderMapping mapping = new OidcProviderMapping();
        apply(mapping, command);
        mapping.setTenantId(tenantId);
        mapping.setRegistrationId(registrationId);
        mapping.setCreatedBy(actorId);
        mapping.setUpdatedBy(actorId);
        mapping.setCreatedAt(LocalDateTime.now());
        mapping.setUpdatedAt(mapping.getCreatedAt());
        mapper.insert(mapping);
        OidcProviderView view = toView(mapping);
        auditLogService.record(tenantId, actorId, "OIDC_PROVIDER", mapping.getId(),
                "OIDC_PROVIDER_CREATED", null, view, "创建企业身份提供商映射");
        return view;
    }

    @Transactional
    public OidcProviderView update(Long id, Long tenantId, Long actorId, OidcProviderCommand command) {
        OidcProviderMapping mapping = requireTenantMapping(id, tenantId);
        OidcProviderView before = toView(mapping);
        String registrationId = normalizeRegistrationId(command.registrationId());
        OidcProviderMapping duplicate = mapper.selectByRegistrationId(registrationId);
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BusinessException("registrationId 已存在");
        }
        mapping.setRegistrationId(registrationId);
        apply(mapping, command);
        mapping.setUpdatedBy(actorId);
        mapping.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(mapping);
        OidcProviderView view = toView(mapping);
        auditLogService.record(tenantId, actorId, "OIDC_PROVIDER", id,
                "OIDC_PROVIDER_UPDATED", before, view, "更新企业身份提供商映射");
        return view;
    }

    @Transactional
    public OidcProviderView setEnabled(Long id, Long tenantId, Long actorId, boolean enabled) {
        OidcProviderMapping mapping = requireTenantMapping(id, tenantId);
        OidcProviderView before = toView(mapping);
        mapping.setEnabled(enabled);
        mapping.setUpdatedBy(actorId);
        mapping.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(mapping);
        OidcProviderView view = toView(mapping);
        auditLogService.record(tenantId, actorId, "OIDC_PROVIDER", id,
                enabled ? "OIDC_PROVIDER_ENABLED" : "OIDC_PROVIDER_DISABLED", before, view,
                enabled ? "启用企业身份提供商" : "停用企业身份提供商");
        return view;
    }

    private void apply(OidcProviderMapping mapping, OidcProviderCommand command) {
        String role = command.defaultRole() == null ? "REQUESTER"
                : command.defaultRole().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role) || roleMapper.selectIdByCode(role) == null) {
            throw new BusinessException("默认角色只能是 ADMIN、AGENT 或 REQUESTER");
        }
        mapping.setDisplayName(required(command.displayName(), "显示名称", 120));
        mapping.setEnabled(command.enabled() == null || command.enabled());
        mapping.setAutoProvision(command.autoProvision() == null || command.autoProvision());
        mapping.setDefaultRole(role);
        mapping.setAllowedEmailDomains(normalizeDomains(command.allowedEmailDomains()));
        mapping.setRequiredGroup(optional(command.requiredGroup(), 191));
        mapping.setUsernameClaim(claim(command.usernameClaim(), "preferred_username"));
        mapping.setDisplayNameClaim(claim(command.displayNameClaim(), "name"));
        mapping.setEmailClaim(claim(command.emailClaim(), "email"));
        mapping.setGroupsClaim(claim(command.groupsClaim(), "groups"));
    }

    private OidcProviderMapping requireTenantMapping(Long id, Long tenantId) {
        OidcProviderMapping mapping = mapper.selectOne(new LambdaQueryWrapper<OidcProviderMapping>()
                .eq(OidcProviderMapping::getId, id)
                .eq(OidcProviderMapping::getTenantId, tenantId));
        if (mapping == null) throw new BusinessException("企业身份提供商不存在");
        return mapping;
    }

    private OidcProviderView toView(OidcProviderMapping mapping) {
        ClientRegistration registration = clientRegistration(mapping.getRegistrationId());
        String issuer = registration == null ? null : registration.getProviderDetails().getIssuerUri();
        return new OidcProviderView(mapping.getId(), mapping.getRegistrationId(), mapping.getDisplayName(),
                mapping.getEnabled(), mapping.getAutoProvision(), mapping.getDefaultRole(),
                mapping.getAllowedEmailDomains(), mapping.getRequiredGroup(), mapping.getUsernameClaim(),
                mapping.getDisplayNameClaim(), mapping.getEmailClaim(), mapping.getGroupsClaim(),
                registration != null, issuer, mapping.getUpdatedAt());
    }

    private ClientRegistration clientRegistration(String registrationId) {
        ClientRegistrationRepository repository = registrationRepositories.getIfAvailable();
        return repository == null ? null : repository.findByRegistrationId(registrationId);
    }

    private String normalizeRegistrationId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!REGISTRATION_ID.matcher(normalized).matches()) {
            throw new BusinessException("registrationId 需为 2-64 位小写字母、数字或连字符");
        }
        return normalized;
    }

    private String normalizeDomains(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = Arrays.stream(value.split(","))
                .map(String::trim).map(item -> item.toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(","));
        if (normalized.length() > 1000) throw new BusinessException("邮箱域名白名单过长");
        return normalized.isBlank() ? null : normalized;
    }

    private String claim(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (!CLAIM_NAME.matcher(normalized).matches()) throw new BusinessException("OIDC Claim 名称格式不正确");
        return normalized;
    }

    private String required(String value, String label, int max) {
        String normalized = optional(value, max);
        if (normalized == null) throw new BusinessException(label + "不能为空");
        return normalized;
    }

    private String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new BusinessException("字段长度不能超过 " + max + " 个字符");
        return normalized;
    }

    public record OidcProviderCommand(String registrationId, String displayName, Boolean enabled,
                                      Boolean autoProvision, String defaultRole, String allowedEmailDomains,
                                      String requiredGroup, String usernameClaim, String displayNameClaim,
                                      String emailClaim, String groupsClaim) { }
    public record OidcProviderView(Long id, String registrationId, String displayName, Boolean enabled,
                                   Boolean autoProvision, String defaultRole, String allowedEmailDomains,
                                   String requiredGroup, String usernameClaim, String displayNameClaim,
                                   String emailClaim, String groupsClaim, boolean clientConfigured,
                                   String issuer, LocalDateTime updatedAt) { }
    public record PublicOidcProvider(String registrationId, String displayName, String authorizationUrl) { }
}
