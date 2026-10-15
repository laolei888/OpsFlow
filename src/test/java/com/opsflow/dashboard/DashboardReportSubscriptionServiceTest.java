package com.opsflow.dashboard;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.messaging.AsyncNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardReportSubscriptionServiceTest {
    @Mock
    private DashboardReportSubscriptionMapper subscriptionMapper;
    @Mock
    private DashboardService dashboardService;
    @Mock
    private AsyncNotificationPublisher asyncNotificationPublisher;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private DashboardReportSubscriptionService subscriptionService;

    @Test
    void createsDailySubscriptionWithNextRunAndAudit() {
        when(subscriptionMapper.insert(any(DashboardReportSubscription.class)))
                .thenAnswer(invocation -> {
                    DashboardReportSubscription saved = invocation.getArgument(0);
                    saved.setId(9L);
                    return 1;
                });

        DashboardReportSubscription result = subscriptionService.create(
                "  每日运营日报  ", "daily", 7, 7L, 3L);

        assertThat(result.getId()).isEqualTo(9L);
        assertThat(result.getName()).isEqualTo("每日运营日报");
        assertThat(result.getFrequency()).isEqualTo(DashboardReportFrequency.DAILY);
        assertThat(result.getRangeDays()).isEqualTo(7);
        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isNotNull();
        verify(auditLogService).record(eq(3L), eq(7L), eq("REPORT_SUBSCRIPTION"), eq(9L),
                eq("REPORT_SUBSCRIPTION_CREATED"), eq(null), eq(result), contains("创建"));
    }

    @Test
    void runsSubscriptionAndCreatesDeduplicatedReportNotification() {
        DashboardReportSubscription subscription = subscription(9L);
        when(subscriptionMapper.selectOne(any(Wrapper.class))).thenReturn(subscription);
        when(dashboardService.report(eq(3L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(report());
        when(asyncNotificationPublisher.enqueue(eq(3L), eq(7L), eq(null), eq("REPORT_READY"),
                contains("运营报表"), contains("新增"), contains("REPORT_SUBSCRIPTION"),
                eq("REPORT_SUBSCRIPTION"), eq(9L)))
                .thenReturn(true);

        DashboardReportSubscription result = subscriptionService.runNow(9L, 7L, 3L);

        assertThat(result.getLastRunAt()).isNotNull();
        assertThat(result.getNextRunAt()).isAfter(result.getLastRunAt());
        verify(asyncNotificationPublisher).enqueue(eq(3L), eq(7L), eq(null), eq("REPORT_READY"),
                contains("运营报表"), contains("新增"), contains("REPORT_SUBSCRIPTION:9:"),
                eq("REPORT_SUBSCRIPTION"), eq(9L));
        verify(subscriptionMapper).updateById(subscription);
        verify(auditLogService).record(eq(3L), eq(7L), eq("REPORT_SUBSCRIPTION"), eq(9L),
                eq("REPORT_SUBSCRIPTION_RUN"), eq(null), eq(subscription), contains("手动"));
    }

    @Test
    void rejectsUnsupportedFrequencyAndRange() {
        assertThatThrownBy(() -> subscriptionService.create("日报", "MONTHLY", 7, 7L, 3L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> subscriptionService.create("日报", "DAILY", 10, 7L, 3L))
                .isInstanceOf(BusinessException.class);
    }

    private DashboardReportSubscription subscription(Long id) {
        DashboardReportSubscription subscription = new DashboardReportSubscription();
        subscription.setId(id);
        subscription.setTenantId(3L);
        subscription.setOwnerId(7L);
        subscription.setName("每日运营日报");
        subscription.setFrequency(DashboardReportFrequency.DAILY);
        subscription.setRangeDays(7);
        subscription.setEnabled(true);
        subscription.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        subscription.setCreatedAt(LocalDateTime.now().minusDays(1));
        subscription.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return subscription;
    }

    private DashboardReport report() {
        LocalDate to = LocalDate.now();
        return new DashboardReport(
                to.minusDays(6), to, 5, 3, 2, 1, 12.5, 80, 5,
                List.of(), List.of());
    }
}
