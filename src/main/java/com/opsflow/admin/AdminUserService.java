package com.opsflow.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.AuthSource;
import com.opsflow.auth.RoleMapper;
import com.opsflow.auth.User;
import com.opsflow.auth.UserMapper;
import com.opsflow.auth.UserRole;
import com.opsflow.auth.UserRoleMapper;
import com.opsflow.auth.UserStatus;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminUserService {
    private static final Set<String> SUPPORTED_ROLES = Set.of("ADMIN", "AGENT", "REQUESTER");

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AuditLogService auditLogService;
    private final PasswordPolicy passwordPolicy;

    public List<AdminUserView> list(Long tenantId) {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, tenantId)
                        .orderByAsc(User::getDisplayName)
                        .orderByAsc(User::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public AdminUserView create(String username, String password, String displayName,
                                String roleCode, Long tenantId, Long adminId) {
        String normalizedUsername = normalizeUsername(username);
        passwordPolicy.validate(password, normalizedUsername);
        String normalizedRole = normalizeRole(roleCode);
        String normalizedDisplayName = displayName == null || displayName.isBlank()
                ? normalizedUsername : displayName.trim();

        if (userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, normalizedUsername)) != null) {
            throw new BusinessException("用户名已存在");
        }
        Long roleId = roleMapper.selectIdByCode(normalizedRole);
        if (roleId == null) throw new BusinessException("角色不存在: " + normalizedRole);

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(normalizedDisplayName);
        user.setAuthSource(AuthSource.LOCAL);
        user.setTenantId(tenantId);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        assignRole(user.getId(), roleId);
        AdminUserView view = toView(user);
        auditLogService.record(tenantId, adminId, "USER", user.getId(), "USER_CREATED",
                null, view, "管理员创建团队账号");
        return view;
    }

    @Transactional
    public AdminUserView changeStatus(Long userId, UserStatus status, Long adminId, Long tenantId) {
        if (status == null) throw new BusinessException("用户状态不能为空");
        User user = findTenantUser(userId, tenantId);
        AdminUserView before = toView(user);
        if (userId.equals(adminId) && status == UserStatus.DISABLED) {
            throw new BusinessException("不能停用当前登录的管理员账号");
        }
        if (user.getStatus() == UserStatus.ACTIVE && status == UserStatus.DISABLED
                && hasRole(user.getId(), "ADMIN")) {
            ensureAnotherActiveAdmin(tenantId, user.getId());
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        if (status == UserStatus.DISABLED) authService.invalidateTokens(user.getId());
        AdminUserView view = toView(user);
        auditLogService.record(tenantId, adminId, "USER", userId, "USER_STATUS_CHANGED",
                before, view, "管理员更新用户状态");
        return view;
    }

    @Transactional
    public AdminUserView changeRole(Long userId, String roleCode, Long adminId, Long tenantId) {
        String normalizedRole = normalizeRole(roleCode);
        User user = findTenantUser(userId, tenantId);
        AdminUserView before = toView(user);
        if (userId.equals(adminId) && !"ADMIN".equals(normalizedRole)) {
            throw new BusinessException("不能移除当前登录管理员的 ADMIN 角色");
        }
        if (user.getStatus() == UserStatus.ACTIVE
                && hasRole(user.getId(), "ADMIN")
                && !"ADMIN".equals(normalizedRole)) {
            ensureAnotherActiveAdmin(tenantId, user.getId());
        }
        Long roleId = roleMapper.selectIdByCode(normalizedRole);
        if (roleId == null) throw new BusinessException("角色不存在: " + normalizedRole);
        userRoleMapper.deleteByUserId(user.getId());
        assignRole(user.getId(), roleId);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        authService.invalidateTokens(user.getId());
        AdminUserView view = toView(user);
        auditLogService.record(tenantId, adminId, "USER", userId, "USER_ROLE_CHANGED",
                before, view, "管理员更新用户角色");
        return view;
    }

    private void ensureAnotherActiveAdmin(Long tenantId, Long excludedUserId) {
        long activeAdmins = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, tenantId)
                        .eq(User::getStatus, UserStatus.ACTIVE))
                .stream()
                .filter(user -> !user.getId().equals(excludedUserId))
                .filter(user -> hasRole(user.getId(), "ADMIN"))
                .count();
        if (activeAdmins == 0) throw new BusinessException("租户至少需要保留一名启用中的管理员");
    }

    private User findTenantUser(Long userId, Long tenantId) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId)
                .eq(User::getTenantId, tenantId));
        if (user == null) throw new BusinessException("用户不存在");
        return user;
    }

    private boolean hasRole(Long userId, String role) {
        return authService.rolesOf(userId).contains(role);
    }

    private AdminUserView toView(User user) {
        return new AdminUserView(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getTenantId(), user.getStatus(), authService.rolesOf(user.getId()),
                user.getAuthSource(), user.getEmail(), user.getLastLoginAt(), user.getCreatedAt());
    }

    private void assignRole(Long userId, Long roleId) {
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 4 || normalized.length() > 64) {
            throw new BusinessException("用户名长度必须为 4-64 位");
        }
        return normalized;
    }

    private String normalizeRole(String roleCode) {
        String normalized = roleCode == null ? "" : roleCode.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ROLES.contains(normalized)) {
            throw new BusinessException("角色只能是 ADMIN、AGENT 或 REQUESTER");
        }
        return normalized;
    }
}
