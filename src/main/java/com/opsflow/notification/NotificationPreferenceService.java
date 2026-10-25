package com.opsflow.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import com.opsflow.security.OutboundUrlPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {
    private static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";
    private static final LocalTime DEFAULT_WINDOW_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_WINDOW_END = LocalTime.of(22, 0);
    private static final Set<String> URGENT_EVENT_TYPES = Set.of(
            "INCIDENT_DECLARED", "INCIDENT_STATUS_CHANGED", "ONCALL_ESCALATION",
            "MONITORING_ALERT", "SLA_OVERDUE");
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
        NotificationChannelProfile profile = findProfile(tenantId, userId);
        String preferredLocale = normalizeLocale(command.preferredLocale());
        String timeZone = normalizeTimeZone(command.timeZone());
        boolean deliveryWindowEnabled = Boolean.TRUE.equals(command.deliveryWindowEnabled());
        LocalTime deliveryWindowStart = command.deliveryWindowStart() == null
                ? DEFAULT_WINDOW_START : command.deliveryWindowStart();
        LocalTime deliveryWindowEnd = command.deliveryWindowEnd() == null
                ? DEFAULT_WINDOW_END : command.deliveryWindowEnd();
        if (deliveryWindowEnabled && deliveryWindowStart.equals(deliveryWindowEnd)) {
            throw new BusinessException("通知发送时间窗的开始和结束时间不能相同");
        }
        boolean urgentBypass = command.urgentBypass() == null || command.urgentBypass();
        String requestedWecomUrl = trim(command.wecomWebhookUrl());
        String wecomWebhookUrl = Boolean.TRUE.equals(command.clearWecomWebhook()) ? null
                : requestedWecomUrl == null ? (profile == null ? null : profile.getWecomWebhookUrl())
                : normalizeWecomWebhookUrl(requestedWecomUrl);
        List<RuleCommand> rules = normalizeRules(command.rules());
        if (rules.stream().anyMatch(RuleCommand::emailEnabled) && emailAddress == null) {
            throw new BusinessException("启用邮件通知前请填写邮件地址");
        }
        if (rules.stream().anyMatch(RuleCommand::webhookEnabled) && webhookUrl == null) {
            throw new BusinessException("启用个人 Webhook 前请填写回调地址");
        }
        if (rules.stream().anyMatch(RuleCommand::wecomEnabled) && wecomWebhookUrl == null) {
            throw new BusinessException("启用企业微信通知前请填写群机器人 Webhook 地址");
        }

        PreferenceView before = get(tenantId, userId);
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
        profile.setWecomWebhookUrl(wecomWebhookUrl);
        profile.setPreferredLocale(preferredLocale);
        profile.setTimeZone(timeZone);
        profile.setDeliveryWindowEnabled(deliveryWindowEnabled);
        profile.setDeliveryWindowStart(deliveryWindowStart);
        profile.setDeliveryWindowEnd(deliveryWindowEnd);
        profile.setUrgentBypass(urgentBypass);
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
            preference.setWecomEnabled(rule.wecomEnabled());
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
        boolean wecom = selected != null && Boolean.TRUE.equals(selected.getWecomEnabled());
        return new ResolvedPreference(inApp, email, webhook, wecom,
                profile == null ? null : profile.getEmailAddress(),
                profile == null ? null : profile.getWebhookUrl(),
                profile == null ? null : profile.getWebhookSecret(),
                profile == null ? null : profile.getWecomWebhookUrl(),
                profile == null ? NotificationTemplateService.DEFAULT_LOCALE : normalizeStoredLocale(profile.getPreferredLocale()),
                profile == null ? DEFAULT_TIME_ZONE : normalizeStoredTimeZone(profile.getTimeZone()),
                profile != null && Boolean.TRUE.equals(profile.getDeliveryWindowEnabled()),
                profile == null || profile.getDeliveryWindowStart() == null ? DEFAULT_WINDOW_START : profile.getDeliveryWindowStart(),
                profile == null || profile.getDeliveryWindowEnd() == null ? DEFAULT_WINDOW_END : profile.getDeliveryWindowEnd(),
                profile == null || !Boolean.FALSE.equals(profile.getUrgentBypass()));
    }

    public DeliveryWindowDecision deliveryWindow(ResolvedPreference preference, String eventType, Instant now) {
        Instant current = now == null ? Instant.now() : now;
        ZoneId systemZone = ZoneId.systemDefault();
        LocalDateTime immediate = LocalDateTime.ofInstant(current, systemZone);
        if (preference == null || !preference.deliveryWindowEnabled()) {
            return new DeliveryWindowDecision(immediate, false, null);
        }
        String normalizedEvent = normalizeEventType(eventType, false);
        if (preference.urgentBypass() && URGENT_EVENT_TYPES.contains(normalizedEvent)) {
            return new DeliveryWindowDecision(immediate, false, "紧急事件已越过发送时间窗");
        }
        ZoneId recipientZone = ZoneId.of(normalizeStoredTimeZone(preference.timeZone()));
        LocalTime start = preference.deliveryWindowStart() == null ? DEFAULT_WINDOW_START : preference.deliveryWindowStart();
        LocalTime end = preference.deliveryWindowEnd() == null ? DEFAULT_WINDOW_END : preference.deliveryWindowEnd();
        ZonedDateTime recipientNow = current.atZone(recipientZone);
        LocalTime currentTime = recipientNow.toLocalTime();
        boolean overnight = start.isAfter(end);
        boolean allowed = overnight
                ? !currentTime.isBefore(start) || currentTime.isBefore(end)
                : !currentTime.isBefore(start) && currentTime.isBefore(end);
        if (allowed) return new DeliveryWindowDecision(immediate, false, null);

        LocalDate targetDate;
        if (overnight) {
            targetDate = recipientNow.toLocalDate();
        } else {
            targetDate = currentTime.isBefore(start)
                    ? recipientNow.toLocalDate() : recipientNow.toLocalDate().plusDays(1);
        }
        ZonedDateTime target = ZonedDateTime.of(targetDate, start, recipientZone);
        LocalDateTime scheduledAt = LocalDateTime.ofInstant(target.toInstant(), systemZone);
        String reason = "接收人发送时间窗为 " + start + "–" + end + "（" + recipientZone.getId() + "）";
        return new DeliveryWindowDecision(scheduledAt, true, reason);
    }

    private NotificationChannelProfile findProfile(Long tenantId, Long userId) {
        return profileMapper.selectOne(new LambdaQueryWrapper<NotificationChannelProfile>()
                .eq(NotificationChannelProfile::getTenantId, tenantId)
                .eq(NotificationChannelProfile::getUserId, userId));
    }

    private PreferenceView toView(NotificationChannelProfile profile, List<RuleView> rules) {
        return new PreferenceView(profile == null ? null : profile.getEmailAddress(),
                profile == null ? null : profile.getWebhookUrl(),
                maskSecret(profile == null ? null : profile.getWebhookSecret()),
                maskWecomUrl(profile == null ? null : profile.getWecomWebhookUrl()),
                profile == null ? NotificationTemplateService.DEFAULT_LOCALE : normalizeStoredLocale(profile.getPreferredLocale()),
                profile == null ? DEFAULT_TIME_ZONE : normalizeStoredTimeZone(profile.getTimeZone()),
                profile != null && Boolean.TRUE.equals(profile.getDeliveryWindowEnabled()),
                profile == null || profile.getDeliveryWindowStart() == null ? DEFAULT_WINDOW_START : profile.getDeliveryWindowStart(),
                profile == null || profile.getDeliveryWindowEnd() == null ? DEFAULT_WINDOW_END : profile.getDeliveryWindowEnd(),
                profile == null || !Boolean.FALSE.equals(profile.getUrgentBypass()), rules);
    }

    private RuleView toRuleView(NotificationEventPreference preference) {
        return new RuleView(preference.getEventType(), Boolean.TRUE.equals(preference.getInAppEnabled()),
                Boolean.TRUE.equals(preference.getEmailEnabled()), Boolean.TRUE.equals(preference.getWebhookEnabled()),
                Boolean.TRUE.equals(preference.getWecomEnabled()));
    }

    private List<RuleCommand> normalizeRules(List<RuleCommand> source) {
        List<RuleCommand> incoming = source == null ? List.of() : source;
        if (incoming.size() > 50) throw new BusinessException("通知规则最多配置 50 条");
        Map<String, RuleCommand> unique = new LinkedHashMap<>();
        for (RuleCommand rule : incoming) {
            if (rule == null) continue;
            String type = normalizeEventType(rule.eventType(), true);
            if (unique.put(type, new RuleCommand(type, rule.inAppEnabled(), rule.emailEnabled(), rule.webhookEnabled(),
                    rule.wecomEnabled())) != null) {
                throw new BusinessException("通知事件规则不能重复：" + type);
            }
        }
        unique.putIfAbsent("*", new RuleCommand("*", true, false, false, false));
        return unique.values().stream()
                .sorted(Comparator.comparing(rule -> "*".equals(rule.eventType()) ? "" : rule.eventType()))
                .toList();
    }

    private String normalizeEmail(String value) {
        String result = trim(value);
        if (result == null) return null;
        if (!isValidEmail(result)) {
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

    private String normalizeWecomWebhookUrl(String value) {
        String result = normalizeWebhookUrl(value);
        if (result == null) return null;
        java.net.URI uri;
        try {
            uri = java.net.URI.create(result);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("企业微信机器人 Webhook 地址格式无效");
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("qyapi.weixin.qq.com") || host.equalsIgnoreCase("webhook.weixin.qq.com"))
                || path == null || !path.startsWith("/cgi-bin/webhook/send")
                || uri.getQuery() == null || !uri.getQuery().contains("key=")) {
            throw new BusinessException("企业微信机器人地址必须使用官方 HTTPS Webhook");
        }
        return result;
    }

    private String normalizeLocale(String value) {
        String result = trim(value);
        if (result == null || result.equalsIgnoreCase("zh") || result.equalsIgnoreCase("zh-cn")
                || result.equalsIgnoreCase("zh_cn")) return NotificationTemplateService.DEFAULT_LOCALE;
        if (result.equalsIgnoreCase("en") || result.equalsIgnoreCase("en-us")
                || result.equalsIgnoreCase("en_us")) return "en-US";
        throw new BusinessException("通知语言仅支持 zh-CN 或 en-US");
    }

    private String normalizeStoredLocale(String value) {
        try {
            return normalizeLocale(value);
        } catch (BusinessException ignored) {
            return NotificationTemplateService.DEFAULT_LOCALE;
        }
    }

    private String normalizeTimeZone(String value) {
        String result = trim(value);
        if (result == null) return DEFAULT_TIME_ZONE;
        try {
            return ZoneId.of(result).getId();
        } catch (RuntimeException invalid) {
            throw new BusinessException("通知时区无效，请使用 IANA 时区，例如 Asia/Shanghai");
        }
    }

    private String normalizeStoredTimeZone(String value) {
        try {
            return normalizeTimeZone(value);
        } catch (BusinessException ignored) {
            return DEFAULT_TIME_ZONE;
        }
    }

    private String normalizeEventType(String value, boolean allowWildcard) {
        String result = trim(value);
        if (allowWildcard && "*".equals(result)) return result;
        if (result == null) {
            throw new BusinessException("通知事件类型格式无效");
        }
        String normalized = result.toUpperCase(Locale.ROOT);
        if (!isValidEventType(normalized)) throw new BusinessException("通知事件类型格式无效");
        return normalized;
    }

    private boolean isValidEmail(String value) {
        if (value.length() > 254) return false;
        int separator = value.indexOf('@');
        if (separator <= 0 || separator != value.lastIndexOf('@') || separator > 64 || separator == value.length() - 1) {
            return false;
        }
        String local = value.substring(0, separator);
        String domain = value.substring(separator + 1);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || domain.length() > 253 || domain.indexOf('.') <= 0 || domain.endsWith(".") || domain.contains("..")) {
            return false;
        }
        for (int i = 0; i < local.length(); i++) {
            char current = local.charAt(i);
            if (!isAsciiLetterOrDigit(current) && "!#$%&'*+-/=?^_`{|}~.".indexOf(current) < 0) return false;
        }
        int labelStart = 0;
        for (int i = 0; i <= domain.length(); i++) {
            if (i < domain.length() && domain.charAt(i) != '.') continue;
            int labelLength = i - labelStart;
            if (labelLength < 1 || labelLength > 63 || domain.charAt(labelStart) == '-'
                    || domain.charAt(i - 1) == '-') return false;
            for (int j = labelStart; j < i; j++) {
                char current = domain.charAt(j);
                if (!isAsciiLetterOrDigit(current) && current != '-') return false;
            }
            labelStart = i + 1;
        }
        return true;
    }

    private boolean isValidEventType(String value) {
        if (value.isEmpty() || value.length() > 64 || value.charAt(0) < 'A' || value.charAt(0) > 'Z') return false;
        for (int i = 1; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current < 'A' || current > 'Z') && (current < '0' || current > '9') && current != '_') return false;
        }
        return true;
    }

    private boolean isAsciiLetterOrDigit(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
    }

    private String maskSecret(String value) {
        if (value == null || value.isBlank()) return null;
        int visible = Math.min(4, value.length());
        return "••••••••" + value.substring(value.length() - visible);
    }

    private String maskWecomUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(value);
            String query = uri.getQuery();
            String key = query == null ? null : java.util.Arrays.stream(query.split("&"))
                    .filter(item -> item.startsWith("key="))
                    .map(item -> item.substring(4))
                    .findFirst().orElse(null);
            String suffix = key == null || key.isBlank() ? "" : key.substring(Math.max(0, key.length() - 4));
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath() + "?key=••••••••" + suffix;
        } catch (IllegalArgumentException invalid) {
            return "企业微信机器人已配置";
        }
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    private RuleView defaultRule() {
        return new RuleView("*", true, false, false, false);
    }

    public record PreferenceCommand(String emailAddress, String webhookUrl, String webhookSecret,
                                    String wecomWebhookUrl, Boolean clearWecomWebhook,
                                    String preferredLocale, String timeZone, Boolean deliveryWindowEnabled,
                                    LocalTime deliveryWindowStart, LocalTime deliveryWindowEnd, Boolean urgentBypass,
                                    List<RuleCommand> rules) {
        public PreferenceCommand(String emailAddress, String webhookUrl, String webhookSecret,
                                 String wecomWebhookUrl, Boolean clearWecomWebhook, List<RuleCommand> rules) {
            this(emailAddress, webhookUrl, webhookSecret, wecomWebhookUrl, clearWecomWebhook,
                    NotificationTemplateService.DEFAULT_LOCALE, DEFAULT_TIME_ZONE, false,
                    DEFAULT_WINDOW_START, DEFAULT_WINDOW_END, true, rules);
        }
    }

    public record RuleCommand(String eventType, boolean inAppEnabled, boolean emailEnabled,
                              boolean webhookEnabled, boolean wecomEnabled) { }

    public record PreferenceView(String emailAddress, String webhookUrl, String webhookSecretPreview,
                                 String wecomWebhookUrlPreview, String preferredLocale, String timeZone,
                                 boolean deliveryWindowEnabled, LocalTime deliveryWindowStart,
                                 LocalTime deliveryWindowEnd, boolean urgentBypass,
                                 List<RuleView> rules) { }

    public record RuleView(String eventType, boolean inAppEnabled, boolean emailEnabled,
                           boolean webhookEnabled, boolean wecomEnabled) { }

    public record ResolvedPreference(boolean inAppEnabled, boolean emailEnabled, boolean webhookEnabled,
                                     boolean wecomEnabled, String emailAddress, String webhookUrl,
                                     String webhookSecret, String wecomWebhookUrl, String preferredLocale,
                                     String timeZone, boolean deliveryWindowEnabled, LocalTime deliveryWindowStart,
                                     LocalTime deliveryWindowEnd, boolean urgentBypass) {
        public ResolvedPreference(boolean inAppEnabled, boolean emailEnabled, boolean webhookEnabled,
                                  boolean wecomEnabled, String emailAddress, String webhookUrl,
                                  String webhookSecret, String wecomWebhookUrl) {
            this(inAppEnabled, emailEnabled, webhookEnabled, wecomEnabled, emailAddress, webhookUrl,
                    webhookSecret, wecomWebhookUrl, NotificationTemplateService.DEFAULT_LOCALE,
                    DEFAULT_TIME_ZONE, false, DEFAULT_WINDOW_START, DEFAULT_WINDOW_END, true);
        }
    }

    public record DeliveryWindowDecision(LocalDateTime nextAttemptAt, boolean delayed, String reason) { }
}
