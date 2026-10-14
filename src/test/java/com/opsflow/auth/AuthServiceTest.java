package com.opsflow.auth;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.security.PasswordPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserMapper userMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private PasswordPolicy passwordPolicy;

    @InjectMocks
    private AuthService authService;

    @Test
    void changesPasswordAndInvalidatesExistingTokenVersion() {
        User user = user(1L);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.matches("new-password", "encoded-old")).thenReturn(false);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");

        authService.changePassword(1L, "old-password", "new-password");

        verify(userMapper).updateById(user);
        verify(auditLogService).record(eq(1L), eq(1L), eq("USER"), eq(1L),
                eq("PASSWORD_CHANGED"), eq(null), eq(null), eq("用户修改登录密码"));
    }

    @Test
    void rejectsWrongCurrentPassword() {
        User user = user(1L);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("wrong-password", "encoded-old")).thenReturn(false);

        assertThrows(RuntimeException.class,
                () -> authService.changePassword(1L, "wrong-password", "new-password"));
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setTenantId(1L);
        user.setPasswordHash("encoded-old");
        user.setTokenVersion(0);
        user.setRoles(List.of("REQUESTER"));
        return user;
    }
}
