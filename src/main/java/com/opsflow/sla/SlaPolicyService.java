package com.opsflow.sla;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.ticket.TicketPriority;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SlaPolicyService {
    private static final int DEFAULT_URGENT_HOURS = 4;
    private static final int DEFAULT_HIGH_HOURS = 8;
    private static final int DEFAULT_MEDIUM_HOURS = 24;
    private static final int DEFAULT_LOW_HOURS = 72;
    private static final int MAX_HOURS = 720;

    private final SlaPolicyMapper slaPolicyMapper;
    private final AuditLogService auditLogService;

    public SlaPolicyView get(Long tenantId) {
        SlaPolicyConfig config = slaPolicyMapper.selectById(tenantId);
        return config == null ? defaults(tenantId) : view(config);
    }

    public Duration durationFor(Long tenantId, TicketPriority priority) {
        SlaPolicyView policy = get(tenantId);
        int hours = switch (priority == null ? TicketPriority.MEDIUM : priority) {
            case URGENT -> policy.urgentHours();
            case HIGH -> policy.highHours();
            case MEDIUM -> policy.mediumHours();
            case LOW -> policy.lowHours();
        };
        return Duration.ofHours(hours);
    }

    public LocalDateTime dueAt(Long tenantId, TicketPriority priority, LocalDateTime createdAt) {
        return createdAt.plus(durationFor(tenantId, priority));
    }

    @Transactional
    public SlaPolicyView update(Long tenantId, int urgentHours, int highHours,
                                int mediumHours, int lowHours, Long actorId) {
        validateHours(urgentHours, "紧急");
        validateHours(highHours, "高优先级");
        validateHours(mediumHours, "中优先级");
        validateHours(lowHours, "低优先级");
        SlaPolicyView before = get(tenantId);
        SlaPolicyConfig config = slaPolicyMapper.selectById(tenantId);
        boolean existing = config != null;
        if (config == null) {
            config = new SlaPolicyConfig();
            config.setTenantId(tenantId);
        }
        config.setUrgentHours(urgentHours);
        config.setHighHours(highHours);
        config.setMediumHours(mediumHours);
        config.setLowHours(lowHours);
        config.setUpdatedBy(actorId);
        config.setUpdatedAt(LocalDateTime.now());
        if (!existing) slaPolicyMapper.insert(config);
        else slaPolicyMapper.updateById(config);
        SlaPolicyView after = view(config);
        auditLogService.record(tenantId, actorId, "SLA_POLICY", tenantId,
                "SLA_POLICY_UPDATED", before, after, "更新租户 SLA 策略");
        return after;
    }

    private void validateHours(int hours, String label) {
        if (hours < 1 || hours > MAX_HOURS) {
            throw new BusinessException(label + " SLA 时长必须在 1-720 小时之间");
        }
    }

    private SlaPolicyView defaults(Long tenantId) {
        return new SlaPolicyView(tenantId, DEFAULT_URGENT_HOURS, DEFAULT_HIGH_HOURS,
                DEFAULT_MEDIUM_HOURS, DEFAULT_LOW_HOURS, null, null);
    }

    private SlaPolicyView view(SlaPolicyConfig config) {
        return new SlaPolicyView(config.getTenantId(), config.getUrgentHours(), config.getHighHours(),
                config.getMediumHours(), config.getLowHours(), config.getUpdatedBy(), config.getUpdatedAt());
    }
}
