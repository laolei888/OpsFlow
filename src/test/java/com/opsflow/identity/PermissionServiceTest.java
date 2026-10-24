package com.opsflow.identity;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.User;
import com.opsflow.auth.UserMapper;
import com.opsflow.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {
    @Mock private AccessPermissionMapper permissionMapper;
    @Mock private UserPermissionOverrideMapper overrideMapper;
    @Mock private UserMapper userMapper;
    @Mock private AuditLogService auditLogService;

    @Test
    void denyOverridesRoleGrantAndAllowAddsMissingPermission() {
        PermissionService service = service();
        when(permissionMapper.selectInheritedCodes(8L)).thenReturn(List.of("TICKET_READ", "TICKET_MANAGE"));
        UserPermissionOverride deny = override(1L, PermissionOverrideEffect.DENY);
        UserPermissionOverride allow = override(2L, PermissionOverrideEffect.ALLOW);
        when(overrideMapper.selectList(any(Wrapper.class))).thenReturn(List.of(deny, allow));
        when(permissionMapper.selectById(1L)).thenReturn(permission(1L, "TICKET_MANAGE"));
        when(permissionMapper.selectById(2L)).thenReturn(permission(2L, "OPERATIONS_READ"));

        List<String> effective = service.effectiveCodes(8L, 3L);

        assertThat(effective).containsExactly("OPERATIONS_READ", "TICKET_READ");
    }

    @Test
    void preventsAdministratorFromDenyingOwnPolicyManagementPermission() {
        PermissionService service = service();
        User user = new User();
        user.setId(8L);
        user.setTenantId(3L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(permissionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(permission(7L, "PERMISSION_MANAGE")));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.replaceOverrides(
                8L, 3L, 8L,
                List.of(new PermissionService.PermissionOverrideCommand(
                        "PERMISSION_MANAGE", PermissionOverrideEffect.DENY, "test"))));

        assertThat(exception.getMessage()).contains("不能拒绝");
    }

    private PermissionService service() {
        return new PermissionService(permissionMapper, overrideMapper, userMapper, auditLogService);
    }

    private UserPermissionOverride override(Long permissionId, PermissionOverrideEffect effect) {
        UserPermissionOverride override = new UserPermissionOverride();
        override.setPermissionId(permissionId);
        override.setEffect(effect);
        return override;
    }

    private AccessPermission permission(Long id, String code) {
        AccessPermission permission = new AccessPermission();
        permission.setId(id);
        permission.setCode(code);
        permission.setResourceName("TEST");
        permission.setActionName("READ");
        permission.setDescription(code);
        return permission;
    }
}
