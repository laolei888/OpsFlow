package com.opsflow.notification;

import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.security.OutboundUrlPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {
    @Mock private NotificationChannelProfileMapper profileMapper;
    @Mock private NotificationEventPreferenceMapper preferenceMapper;
    @Mock private OutboundUrlPolicy outboundUrlPolicy;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private NotificationPreferenceService preferenceService;

    @Test
    void returnsSecureInAppDefaultForNewUser() {
        when(profileMapper.selectOne(any())).thenReturn(null);
        when(preferenceMapper.selectList(any())).thenReturn(List.of());

        NotificationPreferenceService.PreferenceView view = preferenceService.get(1L, 7L);

        assertThat(view.emailAddress()).isNull();
        assertThat(view.webhookSecretPreview()).isNull();
        assertThat(view.preferredLocale()).isEqualTo("zh-CN");
        assertThat(view.timeZone()).isEqualTo("Asia/Shanghai");
        assertThat(view.deliveryWindowEnabled()).isFalse();
        assertThat(view.rules()).containsExactly(
                new NotificationPreferenceService.RuleView("*", true, false, false, false));
    }

    @Test
    void eventSpecificRuleOverridesWildcardRule() {
        NotificationEventPreference wildcard = preference("*", true, false, false);
        NotificationEventPreference incident = preference("INCIDENT_DECLARED", true, true, true);
        NotificationChannelProfile profile = new NotificationChannelProfile();
        profile.setEmailAddress("agent@example.com");
        profile.setWebhookUrl("https://notify.example.com/opsflow");
        profile.setWebhookSecret("secret-value");
        when(preferenceMapper.selectList(any())).thenReturn(List.of(wildcard, incident));
        when(profileMapper.selectOne(any())).thenReturn(profile);

        NotificationPreferenceService.ResolvedPreference resolved = preferenceService.resolve(
                1L, 7L, "incident_declared");

        assertThat(resolved.emailEnabled()).isTrue();
        assertThat(resolved.webhookEnabled()).isTrue();
        assertThat(resolved.wecomEnabled()).isFalse();
        assertThat(resolved.emailAddress()).isEqualTo("agent@example.com");
    }

    @Test
    void masksWecomRobotKeyInPreferenceView() {
        NotificationChannelProfile profile = new NotificationChannelProfile();
        profile.setWecomWebhookUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=secret-1234");
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(preferenceMapper.selectList(any())).thenReturn(List.of());

        NotificationPreferenceService.PreferenceView view = preferenceService.get(1L, 7L);

        assertThat(view.wecomWebhookUrlPreview()).contains("qyapi.weixin.qq.com").endsWith("1234");
        assertThat(view.wecomWebhookUrlPreview()).doesNotContain("secret-");
    }

    @Test
    void rejectsEnabledEmailRuleWithoutAddress() {
        NotificationPreferenceService.PreferenceCommand command = new NotificationPreferenceService.PreferenceCommand(
                null, null, null, null, false,
                List.of(new NotificationPreferenceService.RuleCommand("*", true, true, false, false)));

        assertThatThrownBy(() -> preferenceService.update(command, 1L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邮件地址");
    }

    @Test
    void rejectsAdversarialEmailWithoutRegexBacktracking() {
        String malformed = "!@!." + "!.".repeat(120);
        NotificationPreferenceService.PreferenceCommand command = new NotificationPreferenceService.PreferenceCommand(
                malformed, null, null, null, false,
                List.of(new NotificationPreferenceService.RuleCommand("*", true, false, false, false)));

        assertThatThrownBy(() -> preferenceService.update(command, 1L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邮件地址格式无效");
    }

    @Test
    void delaysNonUrgentExternalDeliveryUntilRecipientsWindow() {
        NotificationPreferenceService.ResolvedPreference preference =
                new NotificationPreferenceService.ResolvedPreference(true, true, false, false,
                        "agent@example.com", null, null, null, "en-US", "Asia/Shanghai",
                        true, LocalTime.of(9, 0), LocalTime.of(18, 0), true);

        NotificationPreferenceService.DeliveryWindowDecision decision = preferenceService.deliveryWindow(
                preference, "REPORT_READY", Instant.parse("2026-10-25T23:00:00Z"));

        assertThat(decision.delayed()).isTrue();
        assertThat(decision.nextAttemptAt().atZone(ZoneId.systemDefault()).toInstant())
                .isEqualTo(Instant.parse("2026-10-26T01:00:00Z"));
        assertThat(decision.reason()).contains("Asia/Shanghai");
    }

    @Test
    void urgentEventBypassesDeliveryWindow() {
        NotificationPreferenceService.ResolvedPreference preference =
                new NotificationPreferenceService.ResolvedPreference(true, true, false, false,
                        "agent@example.com", null, null, null, "zh-CN", "Asia/Shanghai",
                        true, LocalTime.of(9, 0), LocalTime.of(18, 0), true);

        NotificationPreferenceService.DeliveryWindowDecision decision = preferenceService.deliveryWindow(
                preference, "INCIDENT_DECLARED", Instant.parse("2026-10-25T23:00:00Z"));

        assertThat(decision.delayed()).isFalse();
        assertThat(decision.reason()).contains("紧急事件");
    }

    private NotificationEventPreference preference(String type, boolean inApp, boolean email, boolean webhook) {
        NotificationEventPreference preference = new NotificationEventPreference();
        preference.setEventType(type);
        preference.setInAppEnabled(inApp);
        preference.setEmailEnabled(email);
        preference.setWebhookEnabled(webhook);
        preference.setWecomEnabled(false);
        return preference;
    }
}
