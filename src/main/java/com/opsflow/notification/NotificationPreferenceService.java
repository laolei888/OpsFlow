package com.opsflow.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.security.OutboundUrlPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final NotificationChannelProfileMapper profileMapper;
    private final NotificationEventPreferenceMapper preferenceMapper;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final AuditLogService auditLogService;

    public PreferenceView get(Long tenantId, Long userId) {
        NotificationChannelProfile profile = findProfile(tenantId, userId);
        List<NotificationEventPreference> preferences = preferenceMapper.selectList(
                new LambdaQueryWrapper<NotificationEventPreference>()
                        .eq(NotificationEventPreference::getTenantId, tenantId)
                        .eq(NotificationEventPreference::getUserId, userId)
                        .orderByAsc(NotificationEventPreference::getEventType));
        List<RuleView> rules = preferences.stream().map(this::toRuleView).toList();
        if (rules.stream().noneMatch(rule -> "*".equals(rule.eventType()))) {
            List<RuleView> withDefault = new ArrayList<>();
            withDefault.add(defaultRule());
            withDefault.addAll(rules);
            rules = withDefault;
        }
        return toView(profile, rules);
    }

    @Transactional
    public PreferenceView update(PreferenceCommand command, Long tenantId, Long userId) {
        if (command == null) throw new BusinessException("通知偏好不能为空");
        String emailAddress = normalizeEmail(command.emailAddress());
        String webhookUrl = normalizeWebhookUrl(command.webhookUrl());
        List<RuleCommand> rules = normalizeRules(command.rules());
        if (rules.stream().anyMatch(RuleCommand::emailEnabled) && emailAddress == null) {
            throw new BusinessException("启用邮件通知前请填写邮件地址");
        }
        if (rules.stream().anyMatch(RuleCommand::webhookEnabled) && webhookUrl == null) {
            throw new BusinessException("启用个人 Webhook 前请填写回调地址");
        }

        PreferenceView before = get(tenantId, userId);
        NotificationChannelProfile profile = findProfile(tenantId, userId);
        LocalDateTime now = LocalDateTime.now();
        if (profile == null) {
            profile = new NotificationChannelProfile();
            profile.setTenantId(tenantId);
            profile.setUserId(userId);
            profile.setCreatedAt(now);
        }
        String secret = profile.getWebhookSecret();
        if (webhookUrl == null) {
            secret = null;
        } else if (command.webhookSecret() != null && !command.webhookSecret().isBlank()) {
            secret = command.webhookSecret().trim();
            if (secret.length() > 200) throw new BusinessException("Webhook 签名密钥不能超过 200 个字符");
        } else if (secret == null || secret.isBlank()) {
            secret = UUID.randomUUID().toString().replace("-", "");
        }
        profile.setEmailAddress(emailAddress);
        profile.setWebhookUrl(webhookUrl);
        profile.setWebhookSecret(secret);
        profile.setUpdatedAt(now);
        if (profile.getId() == null) profileMapper.insert(profile); else profileMapper.updateById(profile);

        preferenceMapper.delete(new LambdaQueryWrapper<NotificationEventPreference>()
                .eq(NotificationEventPreference::getTenantId, tenantId)
                .eq(NotificationEventPreference::getUserId, userId));
        for (RuleCommand rule : rules) {
            NotificationEventPreference preference = new NotificationEventPreference();
            preference.setTenantId(tenantId);
            preference.setUserId(userId);
            preference.setEventType(rule.eventType());
            preference.setInAppEnabled(rule.inAppEnabled());
            preference.setEmailEnabled(rule.emailEnabled());
            preference.setWebhookEnabled(rule.webhookEnabled());
            preference.setCreatedAt(now);
            preference.setUpdatedAt(now);
            preferenceMapper.insert(preference);
        }

        PreferenceView after = get(tenantId, userId);
        auditLogService.record(tenantId, userId, "NOTIFICATION_PREFERENCE", userId,
                "NOTIFICATION_PREFERENCES_UPDATED", before, after, "更新个人多渠道通知偏好");
        return after;
    }

    public ResolvedPreference resolve(Long tenantId, Long userId, String eventType) {
        String normalizedType = normalizeEventType(eventType, false);
        List<NotificationEventPreference> matches = preferenceMapper.selectList(
                new LambdaQueryWrapper<NotificationEventPreference>()
                        .eq(NotificationEventPreference::getTenantId, tenantId)
                        .eq(NotificationEventPreference::getUserId, userId)
                        .in(NotificationEventPreference::getEventType, List.of(normalizedType, "*")));
        NotificationEventPreference selected = matches.stream()
                .filter(item -> normalizedType.equals(item.getEventType()))
                .findFirst()
                .orElseGet(() -> matches.stream().filter(item -> "*".equals(item.getEventType())).findFirst().orElse(null));
        NotificationChannelProfile profile = findProfile(tenantId, userId);
        boolean inApp = selected == null || Boolean.TRUE.equals(selected.getInAppEnabled());
        boolean email = selected != null && Boolean.TRUE.equals(selected.getEmailEnabled());
        boolean webhook = selected != null && Boolean.TRUE.equals(selected.getWebhookEnabled());
        return new ResolvedPreference(inApp, email, webhook,
                profile == null ? null : profile.getEmailAddress(),
                profile == null ? null : profile.getWebhookUrl(),
                profile == null ? null : profile.getWebhookSecret());
    }

    private NotificationChannelProfile findProfile(Long tenantId, Long userId) {
        return profileMapper.selectOne(new LambdaQueryWrapper<NotificationChannelProfile>()
                .eq(NotificationChannelProfile::getTenantId, tenantId)
                .eq(NotificationChannelProfile::getUserId, userId));
    }

    private PreferenceView toView(NotificationChannelProfile profile, List<RuleView> rules) {
        return new PreferenceView(profile == null ? null : profile.getEmailAddress(),
                profile == null ? null : profile.getWebhookUrl(),
                maskSecret(profile == null ? null : profile.getWebhookSecret()), rules);
    }

    private RuleView toRuleView(NotificationEventPreference preference) {
        return new RuleView(preference.getEventType(), Boolean.TRUE.equals(preference.getInAppEnabled()),
                Boolean.TRUE.equals(preference.getEmailEnabled()), Boolean.TRUE.equals(preference.getWebhookEnabled()));
    }

    private List<RuleCommand> normalizeRules(List<RuleCommand> source) {
        List<RuleCommand> incoming = source == null ? List.of() : source;
        if (incoming.size() > 50) throw new BusinessException("通知规则最多配置 50 条");
        Map<String, RuleCommand> unique = new LinkedHashMap<>();
        for (RuleCommand rule : incoming) {
            if (rule == null) continue;
            String type = normalizeEventType(rule.eventType(), true);
            if (unique.put(type, new RuleCommand(type, rule.inAppEnabled(), rule.emailEnabled(), rule.webhookEnabled())) != null) {
                throw new BusinessException("通知事件规则不能重复：" + type);
            }
        }
        unique.putIfAbsent("*", new RuleCommand("*", true, false, false));
        return unique.values().stream()
                .sorted(Comparator.comparing(rule -> "*".equals(rule.eventType()) ? "" : rule.eventType()))
                .toList();
    }

    private String normalizeEmail(String value) {
        String result = trim(value);
        if (result == null) return null;
        if (result.length() > 254 || !EMAIL_PATTERN.matcher(result).matches()) {
            throw new BusinessException("邮件地址格式无效");
        }
        return result.toLowerCase(Locale.ROOT);
    }

    private String normalizeWebhookUrl(String value) {
        String result = trim(value);
        if (result == null) return null;
        if (result.length() > 1000) throw new BusinessException("Webhook URL 不能超过 1000 个字符");
        outboundUrlPolicy.validate(result);
        return result;
    }

    private String normalizeEventType(String value, boolean allowWildcard) {
        String result = trim(value);
        if (allowWildcard && "*".equals(result)) return result;
        if (result == null || !EVENT_TYPE_PATTERN.matcher(result.toUpperCase(Locale.ROOT)).matches()) {
            throw new BusinessException("通知事件类型格式无效");
        }
        return result.toUpperCase(Locale.ROOT);
    }

    private String maskSecret(String value) {
        if (value == null || value.isBlank()) return null;
        int visible = Math.min(4, value.length());
        return "••••••••" + value.substring(value.length() - visible);
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    private RuleView defaultRule() {
        return new RuleView("*", true, false, false);
    }

    public record PreferenceCommand(String emailAddress, String webhookUrl, String webhookSecret,
                                    List<RuleCommand> rules) { }

    public record RuleCommand(String eventType, boolean inAppEnabled, boolean emailEnabled,
                              boolean webhookEnabled) { }

    public record PreferenceView(String emailAddress, String webhookUrl, String webhookSecretPreview,
                                 List<RuleView> rules) { }

    public record RuleView(String eventType, boolean inAppEnabled, boolean emailEnabled,
                           boolean webhookEnabled) { }

    public record ResolvedPreference(boolean inAppEnabled, boolean emailEnabled, boolean webhookEnabled,
                                     String emailAddress, String webhookUrl, String webhookSecret) { }
}
