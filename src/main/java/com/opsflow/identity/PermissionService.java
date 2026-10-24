package com.opsflow.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.User;
import com.opsflow.auth.UserMapper;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionService {
    private final AccessPermissionMapper permissionMapper;
    private final UserPermissionOverrideMapper overrideMapper;
    private final UserMapper userMapper;
    private final AuditLogService auditLogService;

    public List<String> effectiveCodes(Long userId, Long tenantId) {
        LinkedHashSet<String> effective = new LinkedHashSet<>(permissionMapper.selectInheritedCodes(userId));
        overrides(userId, tenantId).forEach(item -> {
            AccessPermission permission = permissionMapper.selectById(item.getPermissionId());
            if (permission == null) return;
            if (item.getEffect() == PermissionOverrideEffect.DENY) effective.remove(permission.getCode());
            else effective.add(permission.getCode());
        });
        return effective.stream().sorted().toList();
    }

    public List<AccessPermission> catalog() {
        return permissionMapper.selectList(new LambdaQueryWrapper<AccessPermission>()
                .orderByAsc(AccessPermission::getResourceName)
                .orderByAsc(AccessPermission::getCode));
    }

    public UserPermissionView view(Long userId, Long tenantId) {
        requireTenantUser(userId, tenantId);
        List<String> inherited = permissionMapper.selectInheritedCodes(userId);
        Map<Long, AccessPermission> catalog = catalog().stream()
                .collect(Collectors.toMap(AccessPermission::getId, Function.identity()));
        List<PermissionOverrideView> overrideViews = overrides(userId, tenantId).stream()
                .filter(item -> catalog.containsKey(item.getPermissionId()))
                .map(item -> new PermissionOverrideView(catalog.get(item.getPermissionId()).getCode(),
                        item.getEffect(), item.getReason()))
                .toList();
        return new UserPermissionView(userId, inherited, overrideViews, effectiveCodes(userId, tenantId));
    }

    @Transactional
    public UserPermissionView replaceOverrides(Long userId, Long tenantId, Long actorId,
                                               List<PermissionOverrideCommand> commands) {
        User target = requireTenantUser(userId, tenantId);
        List<PermissionOverrideCommand> safeCommands = commands == null ? List.of() : commands;
        Map<String, AccessPermission> byCode = catalog().stream()
                .collect(Collectors.toMap(AccessPermission::getCode, Function.identity()));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (PermissionOverrideCommand command : safeCommands) {
            String code = normalizeCode(command.code());
            if (!seen.add(code)) throw new BusinessException("同一权限不能重复配置");
            if (!byCode.containsKey(code)) throw new BusinessException("权限不存在: " + code);
            if (command.effect() == null) throw new BusinessException("权限效果不能为空");
            if (actorId.equals(userId) && code.equals("PERMISSION_MANAGE")
                    && command.effect() == PermissionOverrideEffect.DENY) {
                throw new BusinessException("不能拒绝当前账号自身的权限策略管理能力");
            }
        }

        UserPermissionView before = view(userId, tenantId);
        overrideMapper.delete(new LambdaQueryWrapper<UserPermissionOverride>()
                .eq(UserPermissionOverride::getTenantId, tenantId)
                .eq(UserPermissionOverride::getUserId, userId));
        LocalDateTime now = LocalDateTime.now();
        List<UserPermissionOverride> inserted = new ArrayList<>();
        for (PermissionOverrideCommand command : safeCommands) {
            AccessPermission permission = byCode.get(normalizeCode(command.code()));
            UserPermissionOverride override = new UserPermissionOverride();
            override.setTenantId(tenantId);
            override.setUserId(userId);
            override.setPermissionId(permission.getId());
            override.setEffect(command.effect());
            override.setReason(normalizeReason(command.reason()));
            override.setCreatedBy(actorId);
            override.setCreatedAt(now);
            override.setUpdatedAt(now);
            overrideMapper.insert(override);
            inserted.add(override);
        }
        target.setTokenVersion(target.getTokenVersion() + 1);
        target.setUpdatedAt(now);
        userMapper.updateById(target);
        UserPermissionView after = view(userId, tenantId);
        auditLogService.record(tenantId, actorId, "USER_PERMISSION", userId,
                "USER_PERMISSIONS_CHANGED", before, after, "管理员更新用户级权限覆盖");
        return after;
    }

    private List<UserPermissionOverride> overrides(Long userId, Long tenantId) {
        return overrideMapper.selectList(new LambdaQueryWrapper<UserPermissionOverride>()
                .eq(UserPermissionOverride::getTenantId, tenantId)
                .eq(UserPermissionOverride::getUserId, userId)
                .orderByAsc(UserPermissionOverride::getPermissionId));
    }

    private User requireTenantUser(Long userId, Long tenantId) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId)
                .eq(User::getTenantId, tenantId));
        if (user == null) throw new BusinessException("用户不存在");
        return user;
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String normalized = reason.trim();
        if (normalized.length() > 300) throw new BusinessException("权限说明不能超过 300 个字符");
        return normalized;
    }

    public record PermissionOverrideCommand(String code, PermissionOverrideEffect effect, String reason) { }
    public record PermissionOverrideView(String code, PermissionOverrideEffect effect, String reason) { }
    public record UserPermissionView(Long userId, List<String> inheritedCodes,
                                     List<PermissionOverrideView> overrides,
                                     List<String> effectiveCodes) { }
}
