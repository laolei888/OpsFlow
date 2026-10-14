package com.opsflow.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DashboardReportSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(DashboardReportSubscriptionService.class);
    private static final Set<Integer> ALLOWED_RANGE_DAYS = Set.of(7, 14, 30);

    private final DashboardReportSubscriptionMapper subscriptionMapper;
    private final DashboardService dashboardService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<DashboardReportSubscription> list(Long ownerId, Long tenantId) {
        return subscriptionMapper.selectList(new LambdaQueryWrapper<DashboardReportSubscription>()
                .eq(DashboardReportSubscription::getOwnerId, ownerId)
                .eq(DashboardReportSubscription::getTenantId, tenantId)
                .orderByDesc(DashboardReportSubscription::getCreatedAt));
    }

    @Transactional
    public DashboardReportSubscription create(String name, String frequency, Integer rangeDays,
                                              Long ownerId, Long tenantId) {
        String safeName = validateName(name);
        DashboardReportFrequency safeFrequency = parseFrequency(frequency);
        validateRangeDays(rangeDays);

        LocalDateTime now = LocalDateTime.now();
        DashboardReportSubscription subscription = new DashboardReportSubscription();
        subscription.setTenantId(tenantId);
        subscription.setOwnerId(ownerId);
        subscription.setName(safeName);
        subscription.setFrequency(safeFrequency);
        subscription.setRangeDays(rangeDays);
        subscription.setEnabled(true);
        subscription.setNextRunAt(nextRunAt(now, safeFrequency));
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        subscriptionMapper.insert(subscription);
        auditLogService.record(tenantId, ownerId, "REPORT_SUBSCRIPTION", subscription.getId(),
                "REPORT_SUBSCRIPTION_CREATED", null, subscription, "创建运营报表订阅");
        return subscription;
    }

    @Transactional
    public DashboardReportSubscription update(Long id, String name, String frequency, Integer rangeDays,
                                              Boolean enabled, Long ownerId, Long tenantId) {
        DashboardReportSubscription subscription = findOwned(id, ownerId, tenantId);
        String safeName = validateName(name);
        DashboardReportFrequency safeFrequency = parseFrequency(frequency);
        validateRangeDays(rangeDays);
        boolean wasEnabled = Boolean.TRUE.equals(subscription.getEnabled());

        DashboardReportSubscription before = copyOf(subscription);
        subscription.setName(safeName);
        subscription.setFrequency(safeFrequency);
        subscription.setRangeDays(rangeDays);
        subscription.setEnabled(enabled);
        if (Boolean.TRUE.equals(enabled) && (!wasEnabled || before.getFrequency() != safeFrequency)) {
            subscription.setNextRunAt(nextRunAt(LocalDateTime.now(), safeFrequency));
        }
        subscription.setUpdatedAt(LocalDateTime.now());
        subscriptionMapper.updateById(subscription);
        auditLogService.record(tenantId, ownerId, "REPORT_SUBSCRIPTION", id,
                "REPORT_SUBSCRIPTION_UPDATED", before, subscription, "更新运营报表订阅");
        return subscription;
    }

    @Transactional
    public void delete(Long id, Long ownerId, Long tenantId) {
        DashboardReportSubscription subscription = findOwned(id, ownerId, tenantId);
        subscriptionMapper.deleteById(id);
        auditLogService.record(tenantId, ownerId, "REPORT_SUBSCRIPTION", id,
                "REPORT_SUBSCRIPTION_DELETED", subscription, null, "删除运营报表订阅");
    }

    @Transactional
    public DashboardReportSubscription runNow(Long id, Long ownerId, Long tenantId) {
        DashboardReportSubscription subscription = findOwned(id, ownerId, tenantId);
        return deliver(subscription, ownerId);
    }

    @Scheduled(
            fixedDelayString = "${opsflow.report.subscription-interval-ms:600000}",
            initialDelayString = "${opsflow.report.subscription-initial-delay-ms:30000}")
    @Transactional
    public void scheduledRun() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<DashboardReportSubscription> dueSubscriptions = subscriptionMapper.selectList(
                    new LambdaQueryWrapper<DashboardReportSubscription>()
                            .eq(DashboardReportSubscription::getEnabled, true)
                            .le(DashboardReportSubscription::getNextRunAt, now)
                            .orderByAsc(DashboardReportSubscription::getNextRunAt)
                            .last("LIMIT 50"));
            for (DashboardReportSubscription subscription : dueSubscriptions) {
                try {
                    deliver(subscription, null);
                } catch (Exception ex) {
                    log.error("Report subscription {} delivery failed", subscription.getId(), ex);
                }
            }
        } catch (Exception ex) {
            log.error("Report subscription scan failed", ex);
        }
    }

    private DashboardReportSubscription deliver(DashboardReportSubscription subscription, Long actorId) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(subscription.getRangeDays() - 1L);
        DashboardReport report = dashboardService.report(subscription.getTenantId(), from, to);
        String dedupeKey = "REPORT_SUBSCRIPTION:" + subscription.getId() + ":" + from + ":" + to;
        String title = "运营报表 · " + subscription.getName();
        String content = report.from() + " 至 " + report.to()
                + "：新增 " + report.createdTickets()
                + "，已解决 " + report.resolvedTickets()
                + "，SLA 达成率 " + report.slaComplianceRate()
                + "%，当前逾期 " + report.overdueTickets() + "。";
        notificationService.notifyIfAbsent(subscription.getTenantId(), subscription.getOwnerId(),
                "REPORT_READY", title, content, dedupeKey);

        LocalDateTime now = LocalDateTime.now();
        subscription.setLastRunAt(now);
        subscription.setNextRunAt(nextRunAt(now, subscription.getFrequency()));
        subscription.setUpdatedAt(now);
        subscriptionMapper.updateById(subscription);
        if (actorId != null) {
            auditLogService.record(subscription.getTenantId(), actorId, "REPORT_SUBSCRIPTION",
                    subscription.getId(), "REPORT_SUBSCRIPTION_RUN", null, subscription,
                    "手动生成运营报表订阅摘要");
        }
        return subscription;
    }

    private DashboardReportSubscription findOwned(Long id, Long ownerId, Long tenantId) {
        DashboardReportSubscription subscription = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<DashboardReportSubscription>()
                        .eq(DashboardReportSubscription::getId, id)
                        .eq(DashboardReportSubscription::getOwnerId, ownerId)
                        .eq(DashboardReportSubscription::getTenantId, tenantId));
        if (subscription == null) throw new BusinessException("报表订阅不存在");
        return subscription;
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) throw new BusinessException("订阅名称不能为空");
        String safeName = name.trim();
        if (safeName.length() > 80) throw new BusinessException("订阅名称不能超过 80 个字符");
        return safeName;
    }

    private DashboardReportFrequency parseFrequency(String frequency) {
        if (frequency == null || frequency.isBlank()) throw new BusinessException("订阅频率不能为空");
        try {
            return DashboardReportFrequency.valueOf(frequency.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("订阅频率仅支持 DAILY 或 WEEKLY");
        }
    }

    private void validateRangeDays(Integer rangeDays) {
        if (rangeDays == null || !ALLOWED_RANGE_DAYS.contains(rangeDays)) {
            throw new BusinessException("报表周期仅支持 7、14 或 30 天");
        }
    }

    private LocalDateTime nextRunAt(LocalDateTime now, DashboardReportFrequency frequency) {
        LocalDate nextDate = now.toLocalDate().plusDays(1);
        if (frequency == DashboardReportFrequency.WEEKLY) {
            while (nextDate.getDayOfWeek() != DayOfWeek.MONDAY) {
                nextDate = nextDate.plusDays(1);
            }
        }
        return nextDate.atTime(9, 0);
    }

    private DashboardReportSubscription copyOf(DashboardReportSubscription source) {
        DashboardReportSubscription copy = new DashboardReportSubscription();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setOwnerId(source.getOwnerId());
        copy.setName(source.getName());
        copy.setFrequency(source.getFrequency());
        copy.setRangeDays(source.getRangeDays());
        copy.setEnabled(source.getEnabled());
        copy.setNextRunAt(source.getNextRunAt());
        copy.setLastRunAt(source.getLastRunAt());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}