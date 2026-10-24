package com.opsflow.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogService auditLogService;
    private final PasswordPolicy passwordPolicy;
    private volatile String dummyPasswordHash;

    @Transactional
    public AuthResponse register(String username, String password, String displayName) {
        String normalizedUsername = normalizeUsername(username);
        passwordPolicy.validate(password, normalizedUsername);
        if (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, normalizedUsername)) != null) {
            throw new BusinessException("用户名已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName == null ? "" : displayName.trim());
        user.setAuthSource(AuthSource.LOCAL);
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

    @Transactional
    public AuthResponse login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        User user = userMapper.selectByUsername(normalizedUsername);
        if (user == null) {
            passwordEncoder.matches(password == null ? "" : password, dummyPasswordHash());
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() != UserStatus.ACTIVE || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(user.getLastLoginAt());
        userMapper.updateById(user);
        user.setRoles(rolesOf(user.getId()));
        return response(user);
    }

    public User findByUsername(String username) {
        User user = userMapper.selectByUsername(normalizeUsername(username));
        if (user == null) throw new BusinessException("用户名或密码错误");
        return user;
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getPasswordHash() == null) {
            throw new BusinessException("当前账号由企业身份提供商管理，请通过单点登录认证");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("当前密码不正确");
        }
        passwordPolicy.validate(newPassword, user.getUsername());
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

    public AuthResponse issueResponse(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException("账号不存在或已停用");
        }
        user.setRoles(rolesOf(userId));
        return response(user);
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
        String canonicalUsername = normalizeUsername(normalizedUsername);
        passwordPolicy.validate(password, canonicalUsername);

        String normalizedRole = roleCode == null ? "AGENT" : roleCode.trim().toUpperCase(Locale.ROOT);
        if (!normalizedRole.equals("AGENT") && !normalizedRole.equals("ADMIN")) {
            throw new BusinessException("初始化账号角色只能是 AGENT 或 ADMIN");
        }
        if (userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, canonicalUsername)) != null) {
            return false;
        }
        if (roleMapper.selectIdByCode(normalizedRole) == null) {
            throw new BusinessException("初始化角色不存在: " + normalizedRole);
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(canonicalUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? "OpsFlow 客服" : displayName.trim());
        user.setAuthSource(AuthSource.LOCAL);
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
                user.getUsername(), user.getDisplayName(), user.getTenantId(), user.getRoles(),
                user.getAuthSource(), user.getEmail());
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String dummyPasswordHash() {
        String current = dummyPasswordHash;
        if (current != null) return current;
        synchronized (this) {
            if (dummyPasswordHash == null) {
                dummyPasswordHash = passwordEncoder.encode("opsflow-dummy-password-never-used");
            }
            return dummyPasswordHash;
        }
    }
}
