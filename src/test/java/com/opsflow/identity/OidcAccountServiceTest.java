package com.opsflow.identity;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthSource;
import com.opsflow.auth.RoleMapper;
import com.opsflow.auth.User;
import com.opsflow.auth.UserMapper;
import com.opsflow.auth.UserRole;
import com.opsflow.auth.UserRoleMapper;
import com.opsflow.auth.UserStatus;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Timeout(10)
class OidcAccountServiceTest {
    @Mock private UserExternalIdentityMapper identityMapper;
    @Mock private UserMapper userMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private AuditLogService auditLogService;

    @Test
    void provisionsFirstLoginWithTenantRoleAndExternalIdentity() {
        when(identityMapper.selectByProviderAndSubject(4L, "subject-7")).thenReturn(null);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, User.class).setId(18L);
            return 1;
        });
        when(roleMapper.selectIdByCode("AGENT")).thenReturn(2L);

        User user = service().authenticate("corp-oidc", Map.of(
                "sub", "subject-7", "iss", "https://id.example.com",
                "email", "agent@example.com", "preferred_username", "agent.one",
                "name", "Agent One", "groups", List.of("opsflow-users")));

        assertThat(user.getId()).isEqualTo(18L);
        assertThat(user.getAuthSource()).isEqualTo(AuthSource.OIDC);
        assertThat(user.getTenantId()).isEqualTo(3L);
        assertThat(user.getPasswordHash()).isNull();
        verify(userRoleMapper).insert(any(UserRole.class));
        ArgumentCaptor<UserExternalIdentity> identity = ArgumentCaptor.forClass(UserExternalIdentity.class);
        verify(identityMapper).insert(identity.capture());
        assertThat(identity.getValue().getSubject()).isEqualTo("subject-7");
    }

    @Test
    void reusesExistingLinkedAccountWithoutImplicitRelinking() {
        UserExternalIdentity identity = new UserExternalIdentity();
        identity.setId(9L);
        identity.setUserId(18L);
        User user = new User();
        user.setId(18L);
        user.setTenantId(3L);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        when(identityMapper.selectByProviderAndSubject(4L, "subject-7")).thenReturn(identity);
        when(userMapper.selectById(18L)).thenReturn(user);

        User result = service().authenticate("corp-oidc", Map.of(
                "sub", "subject-7", "email", "agent@example.com", "groups", List.of("opsflow-users")));

        assertThat(result).isSameAs(user);
        verify(identityMapper).updateById(identity);
        verify(userMapper).updateById(user);
    }
    @Test
    void rejectsEmailOutsideConfiguredEnterpriseDomain() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service().authenticate(
                "corp-oidc", Map.of("sub", "subject-7", "email", "attacker@outside.test",
                        "groups", List.of("opsflow-users"))));

        assertThat(exception.getMessage()).contains("邮箱域名");
    }

    private OidcAccountService service() {
        OidcProviderService providerService = new OidcProviderService(null, null, null, null) {
            @Override
            public OidcProviderMapping requireEnabled(String registrationId) {
                return provider();
            }
        };
        return new OidcAccountService(providerService, identityMapper, userMapper, roleMapper,
                userRoleMapper, auditLogService);
    }

    private OidcProviderMapping provider() {
        OidcProviderMapping provider = new OidcProviderMapping();
        provider.setId(4L);
        provider.setTenantId(3L);
        provider.setRegistrationId("corp-oidc");
        provider.setAutoProvision(true);
        provider.setDefaultRole("AGENT");
        provider.setAllowedEmailDomains("example.com");
        provider.setRequiredGroup("opsflow-users");
        provider.setUsernameClaim("preferred_username");
        provider.setDisplayNameClaim("name");
        provider.setEmailClaim("email");
        provider.setGroupsClaim("groups");
        return provider;
    }
}
