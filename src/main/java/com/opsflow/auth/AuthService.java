package com.opsflow.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogService auditLogService;

    @Transactional
    public AuthResponse register(String username, String password, String displayName) {
        if (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) != null) {
            throw new BusinessException("用户名已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setTenantId(1L);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        assignRole(user.getId(), "REQUESTER");
        user.setRoles(List.of("REQUESTER"));
        return response(user);
    }

    public AuthResponse login(String username, String password) {
        User user = findByUsername(username);
        if (user.getStatus() != UserStatus.ACTIVE || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        user.setRoles(rolesOf(user.getId()));
        return response(user);
    }

    public User findByUsername(String username) {
        User user = userMapper.selectByUsername(username);
        if (user == null) throw new BusinessException("用户名或密码错误");
        return user;
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) {
            throw new BusinessException("新密码长度必须为 8-72 位");
        }
        User user = userMapper.selectById(userId);
        if (user == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("当前密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        auditLogService.record(user.getTenantId(), user.getId(), "USER", user.getId(),
                "PASSWORD_CHANGED", null, null, "用户修改登录密码");
    }

    public List<String> rolesOf(Long userId) {
        return roleMapper.selectCodesByUserId(userId);
    }

    public List<StaffSummary> staffMembers(Long tenantId) {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, tenantId)
                        .eq(User::getStatus, UserStatus.ACTIVE)
                        .orderByAsc(User::getDisplayName))
                .stream()
                .map(user -> new StaffSummary(user.getId(), user.getUsername(), user.getDisplayName(), rolesOf(user.getId())))
                .filter(staff -> staff.roles().stream().anyMatch(role -> role.equals("AGENT") || role.equals("ADMIN")))
                .toList();
    }

    public boolean isStaffMember(Long userId, Long tenantId) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId)
                .eq(User::getTenantId, tenantId)
                .eq(User::getStatus, UserStatus.ACTIVE));
        if (user == null) return false;
        return rolesOf(user.getId()).stream().anyMatch(role -> role.equals("AGENT") || role.equals("ADMIN"));
    }

    @Transactional
    public boolean ensureBootstrapAccount(String username, String password, String displayName,
                                          String roleCode, Long tenantId) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isBlank()) {
            throw new BusinessException("初始化账号用户名不能为空");
        }
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new BusinessException("初始化账号密码长度必须为 8-72 位");
        }

        String normalizedRole = roleCode == null ? "AGENT" : roleCode.trim().toUpperCase(Locale.ROOT);
        if (!normalizedRole.equals("AGENT") && !normalizedRole.equals("ADMIN")) {
            throw new BusinessException("初始化账号角色只能是 AGENT 或 ADMIN");
        }
        if (userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, normalizedUsername)) != null) {
            return false;
        }
        if (roleMapper.selectIdByCode(normalizedRole) == null) {
            throw new BusinessException("初始化角色不存在: " + normalizedRole);
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? "OpsFlow 客服" : displayName.trim());
        user.setTenantId(tenantId == null ? 1L : tenantId);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        assignRole(user.getId(), normalizedRole);
        return true;
    }

    @Transactional
    public void invalidateTokens(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    private void assignRole(Long userId, String code) {
        Long roleId = roleMapper.selectIdByCode(code);
        if (roleId == null) throw new BusinessException("默认角色不存在");
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);
    }

    private AuthResponse response(User user) {
        return new AuthResponse(jwtService.generate(user), jwtService.expiresInSeconds(), user.getId(),
                user.getUsername(), user.getDisplayName(), user.getTenantId(), user.getRoles());
    }
}
