package com.opsflow.capacity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CapacityPolicyService {
    private final CapacityPolicyMapper policyMapper;
    private final CapacityProperties properties;
    private final AuditLogService auditLogService;

    public EffectivePolicy effective(Long tenantId) {
        CapacityPolicy policy = find(tenantId);
        if (policy != null) return toEffective(policy, false);
        CapacityProperties.Thresholds defaults = properties.getThresholds();
        return new EffectivePolicy(null, tenantId,
                defaults.getActiveTickets(), defaults.getOverdueTickets(), defaults.getActiveIncidents(),
                defaults.getFiringAlerts(), defaults.getAsyncBacklog(), defaults.getAttachmentBacklog(),
                defaults.getAttachmentBytes(), defaults.getDatabaseBytes(), 80, 30, true, true, null);
    }

    @Transactional
    public EffectivePolicy save(PolicyCommand command, Long tenantId, Long actorId) {
        validate(command);
        CapacityPolicy policy = find(tenantId);
        CapacityPolicy before = policy == null ? null : copy(policy);
        LocalDateTime now = LocalDateTime.now();
        if (policy == null) {
            policy = new CapacityPolicy();
            policy.setTenantId(tenantId);
            policy.setCreatedBy(actorId);
            policy.setCreatedAt(now);
        }
        apply(policy, command);
        policy.setUpdatedBy(actorId);
        policy.setUpdatedAt(now);
        if (policy.getId() == null) policyMapper.insert(policy);
        else policyMapper.updateById(policy);
        auditLogService.record(tenantId, actorId, "CAPACITY_POLICY", policy.getId(), "CAPACITY_POLICY_UPDATED",
                before, policy, "更新租户容量告警策略");
        return toEffective(policy, false);
    }

    private CapacityPolicy find(Long tenantId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<CapacityPolicy>()
                .eq(CapacityPolicy::getTenantId, tenantId).last("LIMIT 1"));
    }

    private void validate(PolicyCommand command) {
        if (command == null) throw new BusinessException("容量策略不能为空");
        validateThreshold(command.activeTicketsThreshold(), "活跃工单阈值");
        validateThreshold(command.overdueTicketsThreshold(), "逾期工单阈值");
        validateThreshold(command.activeIncidentsThreshold(), "活跃事件阈值");
        validateThreshold(command.firingAlertsThreshold(), "未恢复告警阈值");
        validateThreshold(command.asyncBacklogThreshold(), "异步消息积压阈值");
        validateThreshold(command.attachmentBacklogThreshold(), "附件扫描积压阈值");
        validateThreshold(command.attachmentBytesThreshold(), "附件存储阈值");
        validateThreshold(command.databaseBytesThreshold(), "数据库体积阈值");
        if (command.warningPercent() == null || command.warningPercent() < 50 || command.warningPercent() > 99) {
            throw new BusinessException("预警水位必须在 50% 到 99% 之间");
        }
        if (command.watchDays() == null || command.watchDays() < 1 || command.watchDays() > 365) {
            throw new BusinessException("趋势观察窗口必须在 1 到 365 天之间");
        }
    }

    private void validateThreshold(Long value, String label) {
        if (value == null || value < 0) throw new BusinessException(label + "不能小于 0");
    }

    private void apply(CapacityPolicy policy, PolicyCommand command) {
        policy.setActiveTicketsThreshold(command.activeTicketsThreshold());
        policy.setOverdueTicketsThreshold(command.overdueTicketsThreshold());
        policy.setActiveIncidentsThreshold(command.activeIncidentsThreshold());
        policy.setFiringAlertsThreshold(command.firingAlertsThreshold());
        policy.setAsyncBacklogThreshold(command.asyncBacklogThreshold());
        policy.setAttachmentBacklogThreshold(command.attachmentBacklogThreshold());
        policy.setAttachmentBytesThreshold(command.attachmentBytesThreshold());
        policy.setDatabaseBytesThreshold(command.databaseBytesThreshold());
        policy.setWarningPercent(command.warningPercent());
        policy.setWatchDays(command.watchDays());
        policy.setEnabled(command.enabled() == null || command.enabled());
    }

    private CapacityPolicy copy(CapacityPolicy source) {
        CapacityPolicy copy = new CapacityPolicy();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setActiveTicketsThreshold(source.getActiveTicketsThreshold());
        copy.setOverdueTicketsThreshold(source.getOverdueTicketsThreshold());
        copy.setActiveIncidentsThreshold(source.getActiveIncidentsThreshold());
        copy.setFiringAlertsThreshold(source.getFiringAlertsThreshold());
        copy.setAsyncBacklogThreshold(source.getAsyncBacklogThreshold());
        copy.setAttachmentBacklogThreshold(source.getAttachmentBacklogThreshold());
        copy.setAttachmentBytesThreshold(source.getAttachmentBytesThreshold());
        copy.setDatabaseBytesThreshold(source.getDatabaseBytesThreshold());
        copy.setWarningPercent(source.getWarningPercent());
        copy.setWatchDays(source.getWatchDays());
        copy.setEnabled(source.getEnabled());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private EffectivePolicy toEffective(CapacityPolicy policy, boolean defaults) {
        return new EffectivePolicy(policy.getId(), policy.getTenantId(), policy.getActiveTicketsThreshold(),
                policy.getOverdueTicketsThreshold(), policy.getActiveIncidentsThreshold(),
                policy.getFiringAlertsThreshold(), policy.getAsyncBacklogThreshold(),
                policy.getAttachmentBacklogThreshold(), policy.getAttachmentBytesThreshold(),
                policy.getDatabaseBytesThreshold(), policy.getWarningPercent(), policy.getWatchDays(),
                Boolean.TRUE.equals(policy.getEnabled()), defaults, policy.getUpdatedAt());
    }

    public record PolicyCommand(Long activeTicketsThreshold, Long overdueTicketsThreshold,
                                Long activeIncidentsThreshold, Long firingAlertsThreshold,
                                Long asyncBacklogThreshold, Long attachmentBacklogThreshold,
                                Long attachmentBytesThreshold, Long databaseBytesThreshold,
                                Integer warningPercent, Integer watchDays, Boolean enabled) { }

    public record EffectivePolicy(Long id, Long tenantId, long activeTicketsThreshold,
                                  long overdueTicketsThreshold, long activeIncidentsThreshold,
                                  long firingAlertsThreshold, long asyncBacklogThreshold,
                                  long attachmentBacklogThreshold, long attachmentBytesThreshold,
                                  long databaseBytesThreshold, int warningPercent, int watchDays,
                                  boolean enabled, boolean inheritedDefaults, LocalDateTime updatedAt) { }
}
