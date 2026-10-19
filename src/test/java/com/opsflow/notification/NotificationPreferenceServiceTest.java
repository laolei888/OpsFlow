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
        assertThat(view.rules()).containsExactly(
                new NotificationPreferenceService.RuleView("*", true, false, false));
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
        assertThat(resolved.emailAddress()).isEqualTo("agent@example.com");
    }

    @Test
    void rejectsEnabledEmailRuleWithoutAddress() {
        NotificationPreferenceService.PreferenceCommand command = new NotificationPreferenceService.PreferenceCommand(
                null, null, null, List.of(new NotificationPreferenceService.RuleCommand("*", true, true, false)));

        assertThatThrownBy(() -> preferenceService.update(command, 1L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邮件地址");
    }

    @Test
    void rejectsAdversarialEmailWithoutRegexBacktracking() {
        String malformed = "!@!." + "!.".repeat(120);
        NotificationPreferenceService.PreferenceCommand command = new NotificationPreferenceService.PreferenceCommand(
                malformed, null, null, List.of(new NotificationPreferenceService.RuleCommand("*", true, false, false)));

        assertThatThrownBy(() -> preferenceService.update(command, 1L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邮件地址格式无效");
    }

    private NotificationEventPreference preference(String type, boolean inApp, boolean email, boolean webhook) {
        NotificationEventPreference preference = new NotificationEventPreference();
        preference.setEventType(type);
        preference.setInAppEnabled(inApp);
        preference.setEmailEnabled(email);
        preference.setWebhookEnabled(webhook);
        return preference;
    }
}
