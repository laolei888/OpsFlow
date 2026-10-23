package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CapacityMaintenanceWindowService {
    private final CapacityMaintenanceWindowMapper windowMapper;
    private final AuditLogService auditLogService;

    public List<CapacityMaintenanceWindow> list(Long tenantId) {
        return windowMapper.selectList(new LambdaQueryWrapper<CapacityMaintenanceWindow>()
                .eq(CapacityMaintenanceWindow::getTenantId, tenantId)
                .orderByDesc(CapacityMaintenanceWindow::getEnabled)
                .orderByDesc(CapacityMaintenanceWindow::getStartsAt)
                .last("LIMIT 200"));
    }

    public CapacityMaintenanceWindow active(Long tenantId, LocalDateTime at) {
        return windowMapper.selectOne(new LambdaQueryWrapper<CapacityMaintenanceWindow>()
                .eq(CapacityMaintenanceWindow::getTenantId, tenantId)
                .eq(CapacityMaintenanceWindow::getEnabled, true)
                .le(CapacityMaintenanceWindow::getStartsAt, at)
                .gt(CapacityMaintenanceWindow::getEndsAt, at)
                .orderByDesc(CapacityMaintenanceWindow::getStartsAt)
                .last("LIMIT 1"));
    }

    @Transactional
    public CapacityMaintenanceWindow create(WindowCommand command, Long tenantId, Long actorId) {
        validate(command, tenantId, null);
        LocalDateTime now = LocalDateTime.now();
        CapacityMaintenanceWindow window = new CapacityMaintenanceWindow();
        apply(window, command);
        window.setTenantId(tenantId);
        window.setCreatedBy(actorId);
        window.setUpdatedBy(actorId);
        window.setCreatedAt(now);
        window.setUpdatedAt(now);
        windowMapper.insert(window);
        auditLogService.record(tenantId, actorId, "CAPACITY_MAINTENANCE_WINDOW", window.getId(),
                "CAPACITY_MAINTENANCE_CREATED", null, window, "创建容量维护窗口");
        return window;
    }

    @Transactional
    public CapacityMaintenanceWindow update(Long id, WindowCommand command, Long tenantId, Long actorId) {
        CapacityMaintenanceWindow window = required(id, tenantId);
        validate(command, tenantId, id);
        CapacityMaintenanceWindow before = copy(window);
        apply(window, command);
        window.setUpdatedBy(actorId);
        window.setUpdatedAt(LocalDateTime.now());
        windowMapper.updateById(window);
        auditLogService.record(tenantId, actorId, "CAPACITY_MAINTENANCE_WINDOW", id,
                "CAPACITY_MAINTENANCE_UPDATED", before, window, "更新容量维护窗口");
        return window;
    }

    @Transactional
    public void delete(Long id, Long tenantId, Long actorId) {
        CapacityMaintenanceWindow window = required(id, tenantId);
        windowMapper.deleteById(id);
        auditLogService.record(tenantId, actorId, "CAPACITY_MAINTENANCE_WINDOW", id,
                "CAPACITY_MAINTENANCE_DELETED", window, null, "删除容量维护窗口");
    }

    private void validate(WindowCommand command, Long tenantId, Long excludedId) {
        if (command == null) throw new BusinessException("维护窗口不能为空");
        String name = normalize(command.name());
        String reason = normalize(command.reason());
        if (name == null || name.length() > 120) throw new BusinessException("维护窗口名称不能为空且不能超过 120 个字符");
        if (reason == null || reason.length() > 1000) throw new BusinessException("维护原因不能为空且不能超过 1000 个字符");
        if (command.startsAt() == null || command.endsAt() == null || !command.endsAt().isAfter(command.startsAt())) {
            throw new BusinessException("维护窗口结束时间必须晚于开始时间");
        }
        if (command.endsAt().isAfter(command.startsAt().plusDays(30))) {
            throw new BusinessException("单个容量维护窗口最多持续 30 天");
        }
        if (command.enabled() == null || command.enabled()) {
            LambdaQueryWrapper<CapacityMaintenanceWindow> overlap = new LambdaQueryWrapper<CapacityMaintenanceWindow>()
                    .eq(CapacityMaintenanceWindow::getTenantId, tenantId)
                    .eq(CapacityMaintenanceWindow::getEnabled, true)
                    .lt(CapacityMaintenanceWindow::getStartsAt, command.endsAt())
                    .gt(CapacityMaintenanceWindow::getEndsAt, command.startsAt());
            if (excludedId != null) overlap.ne(CapacityMaintenanceWindow::getId, excludedId);
            if (windowMapper.selectCount(overlap) > 0) throw new BusinessException("该时间段已存在启用中的容量维护窗口");
        }
    }

    private void apply(CapacityMaintenanceWindow window, WindowCommand command) {
        window.setName(normalize(command.name()));
        window.setStartsAt(command.startsAt());
        window.setEndsAt(command.endsAt());
        window.setReason(normalize(command.reason()));
        window.setEnabled(command.enabled() == null || command.enabled());
    }

    private CapacityMaintenanceWindow required(Long id, Long tenantId) {
        CapacityMaintenanceWindow window = windowMapper.selectOne(new LambdaQueryWrapper<CapacityMaintenanceWindow>()
                .eq(CapacityMaintenanceWindow::getId, id)
                .eq(CapacityMaintenanceWindow::getTenantId, tenantId));
        if (window == null) throw new BusinessException("容量维护窗口不存在或不属于当前租户");
        return window;
    }

    private CapacityMaintenanceWindow copy(CapacityMaintenanceWindow source) {
        CapacityMaintenanceWindow copy = new CapacityMaintenanceWindow();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setName(source.getName());
        copy.setStartsAt(source.getStartsAt());
        copy.setEndsAt(source.getEndsAt());
        copy.setReason(source.getReason());
        copy.setEnabled(source.getEnabled());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record WindowCommand(String name, LocalDateTime startsAt, LocalDateTime endsAt,
                                String reason, Boolean enabled) { }
}
