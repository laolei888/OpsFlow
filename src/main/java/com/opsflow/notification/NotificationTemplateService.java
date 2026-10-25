package com.opsflow.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NotificationTemplateService {
    public static final String DEFAULT_LOCALE = "zh-CN";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "title", "content", "eventtype", "notificationid", "tenantid", "recipientid", "ticketid", "createdat");
    private static final Set<NotificationChannel> TEMPLATE_CHANNELS = Set.of(
            NotificationChannel.EMAIL, NotificationChannel.WEBHOOK, NotificationChannel.WECOM);
    private static final Set<String> SUPPORTED_LOCALES = Set.of(DEFAULT_LOCALE, "en-US");

    private final NotificationTemplateMapper templateMapper;
    private final NotificationTemplateVersionMapper versionMapper;
    private final AuditLogService auditLogService;

    public List<TemplateView> list(Long tenantId, String eventType, NotificationChannel channel, String locale) {
        LambdaQueryWrapper<NotificationTemplate> query = new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTenantId, tenantId)
                .orderByDesc(NotificationTemplate::getUpdatedAt)
                .orderByDesc(NotificationTemplate::getId);
        String normalizedType = trim(eventType);
        if (normalizedType != null) query.eq(NotificationTemplate::getEventType, normalizeEventType(normalizedType, true));
        if (channel != null) query.eq(NotificationTemplate::getChannel, requireTemplateChannel(channel));
        String normalizedLocale = normalizeLocale(locale, false);
        if (normalizedLocale != null) query.eq(NotificationTemplate::getLocale, normalizedLocale);
        return templateMapper.selectList(query).stream().map(this::toView).toList();
    }

    public List<TemplateView> list(Long tenantId, String eventType, NotificationChannel channel) {
        return list(tenantId, eventType, channel, null);
    }

    public TemplateDetailView detail(Long id, Long tenantId) {
        NotificationTemplate template = requireTemplate(id, tenantId);
        List<VersionView> versions = versionMapper.selectList(new LambdaQueryWrapper<NotificationTemplateVersion>()
                        .eq(NotificationTemplateVersion::getTenantId, tenantId)
                        .eq(NotificationTemplateVersion::getTemplateId, id)
                        .orderByDesc(NotificationTemplateVersion::getVersionNo))
                .stream().map(this::toVersionView).toList();
        return new TemplateDetailView(toView(template), versions);
    }

    @Transactional
    public TemplateDetailView create(CreateCommand command, Long tenantId, Long actorId) {
        if (command == null) throw new BusinessException("通知模板不能为空");
        String name = requireText(command.name(), "模板名称", 120);
        String description = optionalText(command.description(), "模板说明", 500);
        String eventType = normalizeEventType(command.eventType(), true);
        NotificationChannel channel = requireTemplateChannel(command.channel());
        String locale = normalizeLocale(command.locale(), true);
        validateTemplate(command.titleTemplate(), command.contentTemplate());
        if (templateMapper.selectCount(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTenantId, tenantId)
                .eq(NotificationTemplate::getEventType, eventType)
                .eq(NotificationTemplate::getChannel, channel)
                .eq(NotificationTemplate::getLocale, locale)) > 0) {
            throw new BusinessException("该事件、渠道和语言已经存在通知模板");
        }

        LocalDateTime now = LocalDateTime.now();
        NotificationTemplate template = new NotificationTemplate();
        template.setTenantId(tenantId);
        template.setName(name);
        template.setDescription(description);
        template.setEventType(eventType);
        template.setChannel(channel);
        template.setLocale(locale);
        template.setEnabled(false);
        template.setCreatedBy(actorId);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        try {
            templateMapper.insert(template);
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException("该事件、渠道和语言已经存在通知模板");
        }
        NotificationTemplateVersion version = buildVersion(template, 1, command.titleTemplate(),
                command.contentTemplate(), command.changeNote(), actorId, now);
        versionMapper.insert(version);
        auditLogService.record(tenantId, actorId, "NOTIFICATION_TEMPLATE", template.getId(),
                "NOTIFICATION_TEMPLATE_CREATED", Map.of(), toView(template), "创建通知模板草稿 v1");
        return detail(template.getId(), tenantId);
    }

    @Transactional
    public TemplateView update(Long id, MetadataCommand command, Long tenantId, Long actorId) {
        if (command == null) throw new BusinessException("通知模板配置不能为空");
        NotificationTemplate template = templateMapper.selectForUpdate(id, tenantId);
        if (template == null) throw new BusinessException("通知模板不存在");
        TemplateView before = toView(template);
        template.setName(requireText(command.name(), "模板名称", 120));
        template.setDescription(optionalText(command.description(), "模板说明", 500));
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);
        TemplateView after = toView(template);
        auditLogService.record(tenantId, actorId, "NOTIFICATION_TEMPLATE", id,
                "NOTIFICATION_TEMPLATE_UPDATED", before, after, "更新通知模板元数据");
        return after;
    }

    @Transactional
    public VersionView createVersion(Long templateId, VersionCommand command, Long tenantId, Long actorId) {
        if (command == null) throw new BusinessException("模板版本不能为空");
        validateTemplate(command.titleTemplate(), command.contentTemplate());
        NotificationTemplate template = templateMapper.selectForUpdate(templateId, tenantId);
        if (template == null) throw new BusinessException("通知模板不存在");
        NotificationTemplateVersion latest = versionMapper.selectOne(new LambdaQueryWrapper<NotificationTemplateVersion>()
                .eq(NotificationTemplateVersion::getTenantId, tenantId)
                .eq(NotificationTemplateVersion::getTemplateId, templateId)
                .orderByDesc(NotificationTemplateVersion::getVersionNo)
                .last("LIMIT 1"));
        int nextVersion = latest == null ? 1 : latest.getVersionNo() + 1;
        LocalDateTime now = LocalDateTime.now();
        NotificationTemplateVersion version = buildVersion(template, nextVersion, command.titleTemplate(),
                command.contentTemplate(), command.changeNote(), actorId, now);
        versionMapper.insert(version);
        template.setUpdatedAt(now);
        templateMapper.updateById(template);
        auditLogService.record(tenantId, actorId, "NOTIFICATION_TEMPLATE", templateId,
                "NOTIFICATION_TEMPLATE_VERSION_CREATED", Map.of(), toVersionView(version),
                "创建通知模板草稿 v" + nextVersion);
        return toVersionView(version);
    }

    @Transactional
    public VersionView submit(Long versionId, Long tenantId, Long actorId) {
        NotificationTemplateVersion version = versionMapper.selectForUpdate(versionId, tenantId);
        if (version == null) throw new BusinessException("通知模板版本不存在");
        if (version.getStatus() != NotificationTemplateStatus.DRAFT) {
            throw new BusinessException("只有草稿版本可以提交审核");
        }
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(NotificationTemplateStatus.PENDING_APPROVAL);
        version.setSubmittedBy(actorId);
        version.setSubmittedAt(now);
        version.setReviewedBy(null);
        version.setReviewedAt(null);
        version.setReviewComment(null);
        version.setUpdatedAt(now);
        versionMapper.updateById(version);
        auditLogService.record(tenantId, actorId, "NOTIFICATION_TEMPLATE", version.getTemplateId(),
                "NOTIFICATION_TEMPLATE_SUBMITTED", Map.of("versionId", versionId),
                Map.of("versionId", versionId, "status", version.getStatus()),
                "提交通知模板 v" + version.getVersionNo() + " 审核");
        return toVersionView(version);
    }

    @Transactional
    public VersionView review(Long versionId, ReviewCommand command, Long tenantId, Long actorId) {
        if (command == null || command.decision() == null) throw new BusinessException("审核决定不能为空");
        NotificationTemplateVersion version = versionMapper.selectForUpdate(versionId, tenantId);
        if (version == null) throw new BusinessException("通知模板版本不存在");
        if (version.getStatus() != NotificationTemplateStatus.PENDING_APPROVAL) {
            throw new BusinessException("只有待审核版本可以执行审核");
        }
        if (actorId != null && actorId.equals(version.getCreatedBy())) {
            throw new BusinessException("模板创建人与审核人必须分离");
        }
        String comment = optionalText(command.comment(), "审核意见", 500);
        if (command.decision() == ReviewDecision.REJECT && (comment == null || comment.length() < 5)) {
            throw new BusinessException("驳回时请填写至少 5 个字符的审核意见");
        }
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(command.decision() == ReviewDecision.APPROVE
                ? NotificationTemplateStatus.APPROVED : NotificationTemplateStatus.REJECTED);
        version.setReviewedBy(actorId);
        version.setReviewedAt(now);
        version.setReviewComment(comment);
        version.setUpdatedAt(now);
        versionMapper.updateById(version);
        String action = command.decision() == ReviewDecision.APPROVE
                ? "NOTIFICATION_TEMPLATE_APPROVED" : "NOTIFICATION_TEMPLATE_REJECTED";
        auditLogService.record(tenantId, actorId, "NOTIFICATION_TEMPLATE", version.getTemplateId(), action,
                Map.of("versionId", versionId, "status", NotificationTemplateStatus.PENDING_APPROVAL),
                Map.of("versionId", versionId, "status", version.getStatus()),
                command.decision() == ReviewDecision.APPROVE
                        ? "批准通知模板 v" + version.getVersionNo()
                        : "驳回通知模板 v" + version.getVersionNo() + "：" + comment);
        return toVersionView(version);
    }

    @Transactional
    public TemplateDetailView publish(Long versionId, Long tenantId, Long actorId) {
        NotificationTemplateVersion version = versionMapper.selectForUpdate(versionId, tenantId);
        if (version == null) throw new BusinessException("通知模板版本不存在");
        NotificationTemplate template = templateMapper.selectForUpdate(version.getTemplateId(), tenantId);
        if (template == null) throw new BusinessException("通知模板不存在");
        if (version.getStatus() == NotificationTemplateStatus.PUBLISHED
                && versionId.equals(template.getActiveVersionId()) && Boolean.TRUE.equals(template.getEnabled())) {
            return detail(template.getId(), tenantId);
        }
        if (version.getStatus() != NotificationTemplateStatus.APPROVED) {
            throw new BusinessException("通知模板版本通过审核后才能发布");
        }
        Long previousVersionId = template.getActiveVersionId();
        if (previousVersionId != null && !previousVersionId.equals(versionId)) {
            NotificationTemplateVersion previous = versionMapper.selectForUpdate(previousVersionId, tenantId);
            if (previous != null) {
                previous.setStatus(NotificationTemplateStatus.ARCHIVED);
                previous.setUpdatedAt(LocalDateTime.now());
                versionMapper.updateById(previous);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(NotificationTemplateStatus.PUBLISHED);
        version.setPublishedBy(actorId);
        version.setPublishedAt(now);
        version.setUpdatedAt(now);
        versionMapper.updateById(version);
        template.setActiveVersionId(versionId);
        template.setEnabled(true);
        template.setUpdatedAt(now);
        templateMapper.updateById(template);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("activeVersionId", previousVersionId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("activeVersionId", versionId);
        after.put("versionNo", version.getVersionNo());
        auditLogService.record(tenantId, actorId, "NOTIFICATION_TEMPLATE", template.getId(),
                "NOTIFICATION_TEMPLATE_PUBLISHED", before, after, "发布通知模板 v" + version.getVersionNo());
        return detail(template.getId(), tenantId);
    }

    @Transactional
    public TemplateView setEnabled(Long id, boolean enabled, Long tenantId, Long actorId) {
        NotificationTemplate template = templateMapper.selectForUpdate(id, tenantId);
        if (template == null) throw new BusinessException("通知模板不存在");
        if (enabled && template.getActiveVersionId() == null) throw new BusinessException("请先发布一个模板版本");
        boolean before = Boolean.TRUE.equals(template.getEnabled());
        template.setEnabled(enabled);
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);
        auditLogService.record(tenantId, actorId, "NOTIFICATION_TEMPLATE", id,
                enabled ? "NOTIFICATION_TEMPLATE_ENABLED" : "NOTIFICATION_TEMPLATE_DISABLED",
                Map.of("enabled", before), Map.of("enabled", enabled), enabled ? "启用通知模板" : "停用通知模板");
        return toView(template);
    }

    public PreviewView preview(Long versionId, Long tenantId) {
        NotificationTemplateVersion version = versionMapper.selectOne(new LambdaQueryWrapper<NotificationTemplateVersion>()
                .eq(NotificationTemplateVersion::getId, versionId)
                .eq(NotificationTemplateVersion::getTenantId, tenantId));
        if (version == null) throw new BusinessException("通知模板版本不存在");
        Notification sample = new Notification();
        sample.setId(9001L);
        sample.setTenantId(tenantId);
        sample.setRecipientId(1001L);
        sample.setTicketId(2026L);
        sample.setType("INCIDENT_DECLARED");
        sample.setTitle("订单服务发生高优先级故障");
        sample.setContent("错误率持续超过阈值，请值班人员立即进入事件指挥台响应。");
        sample.setCreatedAt(LocalDateTime.now());
        return new PreviewView(renderText(version.getTitleTemplate(), sample),
                renderText(version.getContentTemplate(), sample), version.getId(), version.getVersionNo());
    }

    public RenderedTemplate render(Long tenantId, NotificationChannel channel, Notification notification,
                                   String preferredLocale) {
        if (!TEMPLATE_CHANNELS.contains(channel) || notification == null) {
            return fallback(notification);
        }
        String eventType = normalizeEventType(notification.getType(), false);
        String locale = normalizeLocale(preferredLocale, true);
        List<String> locales = DEFAULT_LOCALE.equals(locale) ? List.of(DEFAULT_LOCALE) : List.of(locale, DEFAULT_LOCALE);
        List<NotificationTemplate> candidates = templateMapper.selectList(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTenantId, tenantId)
                .eq(NotificationTemplate::getChannel, channel)
                .eq(NotificationTemplate::getEnabled, true)
                .in(NotificationTemplate::getEventType, List.of(eventType, "*"))
                .in(NotificationTemplate::getLocale, locales));
        NotificationTemplate template = findCandidate(candidates, eventType, locale);
        if (template == null && !DEFAULT_LOCALE.equals(locale)) {
            template = findCandidate(candidates, eventType, DEFAULT_LOCALE);
        }
        if (template == null || template.getActiveVersionId() == null) return fallback(notification);
        NotificationTemplateVersion version = versionMapper.selectOne(new LambdaQueryWrapper<NotificationTemplateVersion>()
                .eq(NotificationTemplateVersion::getId, template.getActiveVersionId())
                .eq(NotificationTemplateVersion::getTenantId, tenantId)
                .eq(NotificationTemplateVersion::getTemplateId, template.getId())
                .eq(NotificationTemplateVersion::getStatus, NotificationTemplateStatus.PUBLISHED));
        if (version == null) return fallback(notification);
        return new RenderedTemplate(version.getId(), renderText(version.getTitleTemplate(), notification),
                renderText(version.getContentTemplate(), notification), template.getLocale());
    }

    public RenderedTemplate render(Long tenantId, NotificationChannel channel, Notification notification) {
        return render(tenantId, channel, notification, DEFAULT_LOCALE);
    }

    private NotificationTemplate findCandidate(List<NotificationTemplate> candidates, String eventType, String locale) {
        NotificationTemplate exact = candidates.stream()
                .filter(item -> eventType.equals(item.getEventType()) && locale.equals(templateLocale(item)))
                .findFirst().orElse(null);
        if (exact != null) return exact;
        return candidates.stream()
                .filter(item -> "*".equals(item.getEventType()) && locale.equals(templateLocale(item)))
                .findFirst().orElse(null);
    }

    private String templateLocale(NotificationTemplate template) {
        return template.getLocale() == null ? DEFAULT_LOCALE : template.getLocale();
    }

    private NotificationTemplateVersion buildVersion(NotificationTemplate template, int versionNo, String title,
                                                       String content, String note, Long actorId, LocalDateTime now) {
        NotificationTemplateVersion version = new NotificationTemplateVersion();
        version.setTenantId(template.getTenantId());
        version.setTemplateId(template.getId());
        version.setVersionNo(versionNo);
        version.setStatus(NotificationTemplateStatus.DRAFT);
        version.setTitleTemplate(title.trim());
        version.setContentTemplate(content.trim());
        version.setChangeNote(optionalText(note, "版本说明", 500));
        version.setCreatedBy(actorId);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        return version;
    }

    private void validateTemplate(String title, String content) {
        requireText(title, "标题模板", 300);
        requireText(content, "正文模板", 20000);
        validatePlaceholders(title);
        validatePlaceholders(content);
    }

    private void validatePlaceholders(String source) {
        Matcher matcher = PLACEHOLDER.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!ALLOWED_PLACEHOLDERS.contains(name)) throw new BusinessException("模板包含未知变量：" + matcher.group(1));
        }
        String withoutKnown = matcher.reset().replaceAll("");
        if (withoutKnown.contains("{{") || withoutKnown.contains("}}")) {
            throw new BusinessException("模板变量格式无效，请使用 {{变量名}}");
        }
    }

    private String renderText(String source, Notification notification) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", value(notification.getTitle()));
        values.put("content", value(notification.getContent()));
        values.put("eventtype", value(notification.getType()));
        values.put("notificationid", value(notification.getId()));
        values.put("tenantid", value(notification.getTenantId()));
        values.put("recipientid", value(notification.getRecipientId()));
        values.put("ticketid", value(notification.getTicketId()));
        values.put("createdat", notification.getCreatedAt() == null ? "" : notification.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        Matcher matcher = PLACEHOLDER.matcher(source);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = values.getOrDefault(matcher.group(1).toLowerCase(Locale.ROOT), "");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private RenderedTemplate fallback(Notification notification) {
        return new RenderedTemplate(null, notification == null ? "" : value(notification.getTitle()),
                notification == null ? "" : value(notification.getContent()), DEFAULT_LOCALE);
    }

    private TemplateView toView(NotificationTemplate template) {
        NotificationTemplateVersion active = template.getActiveVersionId() == null ? null
                : versionMapper.selectById(template.getActiveVersionId());
        long versions = versionMapper.selectCount(new LambdaQueryWrapper<NotificationTemplateVersion>()
                .eq(NotificationTemplateVersion::getTenantId, template.getTenantId())
                .eq(NotificationTemplateVersion::getTemplateId, template.getId()));
        return new TemplateView(template.getId(), template.getName(), template.getDescription(), template.getEventType(),
                template.getChannel(), templateLocale(template), Boolean.TRUE.equals(template.getEnabled()), template.getActiveVersionId(),
                active == null ? null : active.getVersionNo(), versions, template.getCreatedBy(), template.getCreatedAt(),
                template.getUpdatedAt());
    }

    private VersionView toVersionView(NotificationTemplateVersion version) {
        return new VersionView(version.getId(), version.getTemplateId(), version.getVersionNo(), version.getStatus(),
                version.getTitleTemplate(), version.getContentTemplate(), version.getChangeNote(), version.getCreatedBy(),
                version.getSubmittedBy(), version.getSubmittedAt(), version.getReviewedBy(), version.getReviewedAt(),
                version.getReviewComment(),
                version.getPublishedBy(), version.getPublishedAt(), version.getCreatedAt(), version.getUpdatedAt());
    }

    private NotificationTemplate requireTemplate(Long id, Long tenantId) {
        NotificationTemplate template = templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getId, id).eq(NotificationTemplate::getTenantId, tenantId));
        if (template == null) throw new BusinessException("通知模板不存在");
        return template;
    }

    private NotificationChannel requireTemplateChannel(NotificationChannel channel) {
        if (channel == null || !TEMPLATE_CHANNELS.contains(channel)) {
            throw new BusinessException("模板渠道仅支持 EMAIL、WEBHOOK 或 WECOM");
        }
        return channel;
    }

    private String normalizeLocale(String value, boolean useDefault) {
        String result = trim(value);
        if (result == null) return useDefault ? DEFAULT_LOCALE : null;
        String normalized = result.replace('_', '-');
        if (normalized.equalsIgnoreCase("zh") || normalized.equalsIgnoreCase("zh-cn")) return DEFAULT_LOCALE;
        if (normalized.equalsIgnoreCase("en") || normalized.equalsIgnoreCase("en-us")) return "en-US";
        if (!SUPPORTED_LOCALES.contains(normalized)) throw new BusinessException("模板语言仅支持 zh-CN 或 en-US");
        return normalized;
    }

    private String normalizeEventType(String value, boolean allowWildcard) {
        String result = trim(value);
        if (allowWildcard && "*".equals(result)) return result;
        if (result == null) throw new BusinessException("通知事件类型不能为空");
        String normalized = result.toUpperCase(Locale.ROOT);
        if (normalized.length() > 64 || normalized.charAt(0) < 'A' || normalized.charAt(0) > 'Z') {
            throw new BusinessException("通知事件类型格式无效");
        }
        for (int index = 1; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if ((current < 'A' || current > 'Z') && (current < '0' || current > '9') && current != '_') {
                throw new BusinessException("通知事件类型格式无效");
            }
        }
        return normalized;
    }

    private String requireText(String value, String label, int max) {
        String result = trim(value);
        if (result == null) throw new BusinessException(label + "不能为空");
        if (result.length() > max) throw new BusinessException(label + "不能超过 " + max + " 个字符");
        return result;
    }

    private String optionalText(String value, String label, int max) {
        String result = trim(value);
        if (result != null && result.length() > max) throw new BusinessException(label + "不能超过 " + max + " 个字符");
        return result;
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record CreateCommand(String name, String description, String eventType, NotificationChannel channel,
                                String locale, String titleTemplate, String contentTemplate, String changeNote) {
        public CreateCommand(String name, String description, String eventType, NotificationChannel channel,
                             String titleTemplate, String contentTemplate, String changeNote) {
            this(name, description, eventType, channel, DEFAULT_LOCALE, titleTemplate, contentTemplate, changeNote);
        }
    }
    public record MetadataCommand(String name, String description) { }
    public record VersionCommand(String titleTemplate, String contentTemplate, String changeNote) { }
    public record TemplateView(Long id, String name, String description, String eventType, NotificationChannel channel,
                               String locale, boolean enabled, Long activeVersionId, Integer activeVersionNo, long versionCount,
                               Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record VersionView(Long id, Long templateId, Integer versionNo, NotificationTemplateStatus status,
                              String titleTemplate, String contentTemplate, String changeNote, Long createdBy,
                              Long submittedBy, LocalDateTime submittedAt, Long reviewedBy, LocalDateTime reviewedAt,
                              String reviewComment,
                              Long publishedBy, LocalDateTime publishedAt, LocalDateTime createdAt,
                              LocalDateTime updatedAt) { }
    public record TemplateDetailView(TemplateView template, List<VersionView> versions) { }
    public record PreviewView(String title, String content, Long versionId, Integer versionNo) { }
    public record RenderedTemplate(Long versionId, String title, String content, String locale) {
        public RenderedTemplate(Long versionId, String title, String content) {
            this(versionId, title, content, DEFAULT_LOCALE);
        }
    }
    public enum ReviewDecision { APPROVE, REJECT }
    public record ReviewCommand(ReviewDecision decision, String comment) { }
}
