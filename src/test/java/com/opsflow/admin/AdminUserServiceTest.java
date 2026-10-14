package com.opsflow.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.RoleMapper;
import com.opsflow.auth.User;
import com.opsflow.auth.UserMapper;
import com.opsflow.auth.UserRole;
import com.opsflow.auth.UserRoleMapper;
import com.opsflow.auth.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {
    @Mock
    private UserMapper userMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthService authService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AdminUserService adminUserService;

    @Test
    void createsActiveUserAndAuditsWithoutExposingPassword() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(roleMapper.selectIdByCode("AGENT")).thenReturn(2L);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, User.class).setId(8L);
            return 1;
        });
        when(authService.rolesOf(8L)).thenReturn(List.of("AGENT"));

        AdminUserView created = adminUserService.create("agent01", "password123", "客服一号",
                "agent", 3L, 1L);

        assertEquals(8L, created.id());
        assertEquals(UserStatus.ACTIVE, created.status());
        assertEquals(List.of("AGENT"), created.roles());
        verify(userRoleMapper).insert(any(UserRole.class));
        verify(auditLogService).record(eq(3L), eq(1L), eq("USER"), eq(8L),
                eq("USER_CREATED"), eq(null), any(), eq("管理员创建团队账号"));
    }

    @Test
    void refusesToDisableTheLastActiveAdmin() {
        User admin = new User();
        admin.setId(1L);
        admin.setTenantId(3L);
        admin.setStatus(UserStatus.ACTIVE);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(admin);
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(admin));
        when(authService.rolesOf(1L)).thenReturn(List.of("ADMIN"));

        assertThrows(RuntimeException.class,
                () -> adminUserService.changeStatus(1L, UserStatus.DISABLED, 9L, 3L));

        verify(userMapper, never()).updateById(any(User.class));
    }
}
