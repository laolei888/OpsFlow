package com.opsflow.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NotificationDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long CLAIM_LEASE_SECONDS = 60L;

    private final NotificationDeliveryMapper deliveryMapper;
    private final NotificationMapper notificationMapper;
    private final NotificationPreferenceService preferenceService;
    private final NotificationTemplateService templateService;
    private final AuditLogService auditLogService;
    private final NotificationDeliveryMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Map<NotificationChannel, NotificationChannelAdapter> adapters;

    public NotificationDeliveryService(NotificationDeliveryMapper deliveryMapper,
                                       NotificationMapper notificationMapper,
                                       NotificationPreferenceService preferenceService,
                                       NotificationTemplateService templateService,
                                       AuditLogService auditLogService,
                                       NotificationDeliveryMetrics metrics,
                                       PlatformTransactionManager transactionManager,
                                       List<NotificationChannelAdapter> adapters) {
        this.deliveryMapper = deliveryMapper;
        this.notificationMapper = notificationMapper;
        this.preferenceService = preferenceService;
        this.templateService = templateService;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.adapters = new EnumMap<>(NotificationChannel.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.channel(), adapter));
    }

    public NotificationPreferenceService.ResolvedPreference resolve(Long tenantId, Long recipientId,
                                                                     String notificationType) {
        return preferenceService.resolve(tenantId, recipientId, notificationType);
    }

    @Transactional
    public void plan(Notification notification, NotificationPreferenceService.ResolvedPreference preference) {
        LocalDateTime now = LocalDateTime.now();
        boolean inAppCreated = create(notification, NotificationChannel.IN_APP, null, null,
                preference.inAppEnabled() ? NotificationDeliveryStatus.DELIVERED : NotificationDeliveryStatus.SKIPPED,
                preference.inAppEnabled() ? null : "用户已关闭该事件的站内通知", now);
        if (inAppCreated && preference.inAppEnabled()) metrics.succeeded(NotificationChannel.IN_APP);
        if (preference.emailEnabled()) {
            create(notification, NotificationChannel.EMAIL, preference.emailAddress(), null,
                    destinationStatus(preference.emailAddress()), missingDestination(preference.emailAddress(), "邮件地址"), now);
        }
        if (preference.webhookEnabled()) {
            create(notification, NotificationChannel.WEBHOOK, preference.webhookUrl(), preference.webhookSecret(),
                    destinationStatus(preference.webhookUrl()), missingDestination(preference.webhookUrl(), "Webhook 地址"), now);
        }
        if (preference.wecomEnabled()) {
            create(notification, NotificationChannel.WECOM, preference.wecomWebhookUrl(), null,
                    destinationStatus(preference.wecomWebhookUrl()),
                    missingDestination(preference.wecomWebhookUrl(), "企业微信机器人地址"), now);
        }
    }

    public int deliverPending(int batchSize) {
        int safeBatch = Math.max(1, Math.min(batchSize, 100));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusSeconds(CLAIM_LEASE_SECONDS);
        List<NotificationDelivery> due = deliveryMapper.selectList(new LambdaQueryWrapper<NotificationDelivery>()
                .and(query -> query
                        .and(ready -> ready.in(NotificationDelivery::getStatus,
                                        List.of(NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.RETRYING))
                                .le(NotificationDelivery::getNextAttemptAt, now))
                        .or(stale -> stale.eq(NotificationDelivery::getStatus, NotificationDeliveryStatus.PROCESSING)
                                .le(NotificationDelivery::getUpdatedAt, staleBefore)))
                .orderByAsc(NotificationDelivery::getNextAttemptAt)
                .orderByAsc(NotificationDelivery::getId)
                .last("LIMIT " + safeBatch));
        int processed = 0;
        for (NotificationDelivery item : due) {
            try {
                deliverOne(item.getId(), item.getTenantId());
                processed++;
            } catch (Exception ex) {
                log.warn("Notification delivery {} failed unexpectedly", item.getId(), ex);
            }
        }
        return processed;
    }

    public void deliverOne(Long deliveryId, Long tenantId) {
        DeliveryAttempt attempt = transactionTemplate.execute(status -> claim(deliveryId, tenantId));
        if (attempt == null) return;
        NotificationChannelAdapter adapter = adapters.get(attempt.delivery().getChannel());
        try {
            if (adapter == null) {
                throw new NotificationDeliveryException(
                        attempt.delivery().getChannel() + " 通道尚未在当前环境启用", null, false);
            }
            NotificationChannelAdapter.DeliveryResult result = adapter.deliver(
                    new NotificationChannelAdapter.DeliveryContext(attempt.delivery(), attempt.notification()));
            transactionTemplate.executeWithoutResult(status -> completeSuccess(attempt, result));
            metrics.succeeded(attempt.delivery().getChannel());
        } catch (Exception ex) {
            transactionTemplate.executeWithoutResult(status -> completeFailure(attempt, ex));
            metrics.failed(attempt.delivery().getChannel());
        }
    }

    public List<DeliveryView> search(Long tenantId, NotificationChannel channel,
                                     NotificationDeliveryStatus status, String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<NotificationDelivery> query = new LambdaQueryWrapper<NotificationDelivery>()
                .eq(NotificationDelivery::getTenantId, tenantId)
                .orderByDesc(NotificationDelivery::getCreatedAt)
                .orderByDesc(NotificationDelivery::getId)
                .last("LIMIT " + safeLimit);
        if (channel != null) query.eq(NotificationDelivery::getChannel, channel);
        if (status != null) query.eq(NotificationDelivery::getStatus, status);
        String normalizedKeyword = trim(keyword);
        if (normalizedKeyword != null) {
            query.and(item -> item.like(NotificationDelivery::getNotificationType, normalizedKeyword)
                    .or().like(NotificationDelivery::getDestination, normalizedKeyword));
        }
        List<NotificationDelivery> deliveries = deliveryMapper.selectList(query);
        Map<Long, Notification> notifications = notificationMap(deliveries);
        return deliveries.stream().map(item -> toView(item, notifications.get(item.getNotificationId()))).toList();
    }

    public DeliverySummary summary(Long tenantId) {
        long pending = count(tenantId, NotificationDeliveryStatus.PENDING);
        long processing = count(tenantId, NotificationDeliveryStatus.PROCESSING);
        long retrying = count(tenantId, NotificationDeliveryStatus.RETRYING);
        long delivered = count(tenantId, NotificationDeliveryStatus.DELIVERED);
        long dead = count(tenantId, NotificationDeliveryStatus.DEAD);
        long skipped = count(tenantId, NotificationDeliveryStatus.SKIPPED);
        NotificationDelivery oldest = deliveryMapper.selectOne(new LambdaQueryWrapper<NotificationDelivery>()
                .eq(NotificationDelivery::getTenantId, tenantId)
                .in(NotificationDelivery::getStatus, List.of(NotificationDeliveryStatus.PENDING,
                        NotificationDeliveryStatus.PROCESSING, NotificationDeliveryStatus.RETRYING))
                .orderByAsc(NotificationDelivery::getCreatedAt)
                .last("LIMIT 1"));
        return new DeliverySummary(pending, processing, retrying, delivered, dead, skipped,
                oldest == null ? null : oldest.getCreatedAt());
    }

    public void refreshMetrics() {
        long pending = countAll(NotificationDeliveryStatus.PENDING)
                + countAll(NotificationDeliveryStatus.PROCESSING)
                + countAll(NotificationDeliveryStatus.RETRYING);
        metrics.updateBacklog(pending, countAll(NotificationDeliveryStatus.DEAD));
    }

    @Transactional
    public DeliveryView retry(Long id, Long tenantId, Long actorId, String reason) {
        String normalizedReason = trim(reason);
        if (normalizedReason == null || normalizedReason.length() < 5 || normalizedReason.length() > 500) {
            throw new BusinessException("重试原因需填写 5 到 500 个字符");
        }
        NotificationDelivery delivery = deliveryMapper.selectForUpdate(id, tenantId);
        if (delivery == null) throw new BusinessException("通知投递记录不存在");
        if (delivery.getStatus() == NotificationDeliveryStatus.DELIVERED) {
            throw new BusinessException("已送达的通知无需重试");
        }
        if (delivery.getChannel() == NotificationChannel.IN_APP) {
            throw new BusinessException("站内信由用户偏好即时决定，不能人工重试");
        }
        if (delivery.getStatus() == NotificationDeliveryStatus.PROCESSING
                && delivery.getUpdatedAt() != null
                && delivery.getUpdatedAt().isAfter(LocalDateTime.now().minusSeconds(CLAIM_LEASE_SECONDS))) {
            throw new BusinessException("通知正在投递中，请稍后再试");
        }
        NotificationDeliveryStatus before = delivery.getStatus();
        refreshDestination(delivery);
        LocalDateTime now = LocalDateTime.now();
        delivery.setStatus(NotificationDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(now);
        delivery.setResponseStatus(null);
        delivery.setLastError(null);
        delivery.setDeliveredAt(null);
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
        auditLogService.record(tenantId, actorId, "NOTIFICATION_DELIVERY", id,
                "NOTIFICATION_DELIVERY_RETRY", Map.of("status", before),
                Map.of("status", NotificationDeliveryStatus.PENDING, "channel", delivery.getChannel()), normalizedReason);
        Notification notification = notificationMapper.selectById(delivery.getNotificationId());
        return toView(delivery, notification);
    }

    private void refreshDestination(NotificationDelivery delivery) {
        NotificationPreferenceService.ResolvedPreference current = preferenceService.resolve(
                delivery.getTenantId(), delivery.getRecipientId(), delivery.getNotificationType());
        if (delivery.getChannel() == NotificationChannel.EMAIL) {
            if (!current.emailEnabled() || trim(current.emailAddress()) == null) {
                throw new BusinessException("接收人当前未启用该事件的邮件通知，无法重试");
            }
            delivery.setDestination(current.emailAddress());
            delivery.setDeliverySecret(null);
            return;
        }
        if (delivery.getChannel() == NotificationChannel.WEBHOOK) {
            if (!current.webhookEnabled() || trim(current.webhookUrl()) == null
                    || trim(current.webhookSecret()) == null) {
                throw new BusinessException("接收人当前未完整配置该事件的个人 Webhook，无法重试");
            }
            delivery.setDestination(current.webhookUrl());
            delivery.setDeliverySecret(current.webhookSecret());
            return;
        }
        if (delivery.getChannel() == NotificationChannel.WECOM) {
            if (!current.wecomEnabled() || trim(current.wecomWebhookUrl()) == null) {
                throw new BusinessException("接收人当前未启用该事件的企业微信通知，无法重试");
            }
            delivery.setDestination(current.wecomWebhookUrl());
            delivery.setDeliverySecret(null);
        }
    }

    private DeliveryAttempt claim(Long id, Long tenantId) {
        NotificationDelivery delivery = deliveryMapper.selectForUpdate(id, tenantId);
        if (delivery == null || delivery.getStatus() == NotificationDeliveryStatus.DELIVERED
                || delivery.getStatus() == NotificationDeliveryStatus.DEAD
                || delivery.getStatus() == NotificationDeliveryStatus.SKIPPED) return null;
        LocalDateTime now = LocalDateTime.now();
        if (delivery.getStatus() == NotificationDeliveryStatus.PROCESSING
                && delivery.getUpdatedAt() != null
                && delivery.getUpdatedAt().isAfter(now.minusSeconds(CLAIM_LEASE_SECONDS))) return null;
        if (delivery.getStatus() != NotificationDeliveryStatus.PROCESSING
                && delivery.getNextAttemptAt() != null && delivery.getNextAttemptAt().isAfter(now)) return null;
        Notification notification = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getId, delivery.getNotificationId())
                .eq(Notification::getTenantId, tenantId));
        if (notification == null) {
            delivery.setStatus(NotificationDeliveryStatus.DEAD);
            delivery.setLastError("关联通知不存在");
            delivery.setUpdatedAt(now);
            deliveryMapper.updateById(delivery);
            return null;
        }
        int attemptCount = (delivery.getAttemptCount() == null ? 0 : delivery.getAttemptCount()) + 1;
        delivery.setAttemptCount(attemptCount);
        delivery.setStatus(NotificationDeliveryStatus.PROCESSING);
        delivery.setResponseStatus(null);
        delivery.setLastError(null);
        delivery.setNextAttemptAt(now.plusSeconds(CLAIM_LEASE_SECONDS));
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
        return new DeliveryAttempt(delivery, notification, attemptCount);
    }

    private void completeSuccess(DeliveryAttempt attempt, NotificationChannelAdapter.DeliveryResult result) {
        NotificationDelivery delivery = currentProcessing(attempt);
        if (delivery == null) return;
        LocalDateTime now = LocalDateTime.now();
        delivery.setStatus(NotificationDeliveryStatus.DELIVERED);
        delivery.setResponseStatus(result == null ? null : result.responseStatus());
        delivery.setLastError(null);
        delivery.setDeliveredAt(now);
        delivery.setNextAttemptAt(now);
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
    }

    private void completeFailure(DeliveryAttempt attempt, Exception failure) {
        NotificationDelivery delivery = currentProcessing(attempt);
        if (delivery == null) return;
        NotificationDeliveryException known = failure instanceof NotificationDeliveryException typed ? typed : null;
        boolean retryable = known == null || known.retryable();
        boolean dead = !retryable || attempt.attemptCount() >= MAX_ATTEMPTS;
        LocalDateTime now = LocalDateTime.now();
        delivery.setStatus(dead ? NotificationDeliveryStatus.DEAD : NotificationDeliveryStatus.RETRYING);
        delivery.setResponseStatus(known == null ? null : known.responseStatus());
        delivery.setLastError(safeError(failure));
        delivery.setNextAttemptAt(dead ? now : now.plusSeconds(backoffSeconds(attempt.attemptCount())));
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
    }

    private NotificationDelivery currentProcessing(DeliveryAttempt attempt) {
        NotificationDelivery delivery = deliveryMapper.selectForUpdate(
                attempt.delivery().getId(), attempt.delivery().getTenantId());
        if (delivery == null || delivery.getStatus() != NotificationDeliveryStatus.PROCESSING
                || !Integer.valueOf(attempt.attemptCount()).equals(delivery.getAttemptCount())) return null;
        return delivery;
    }

    private boolean create(Notification notification, NotificationChannel channel, String destination,
                           String secret, NotificationDeliveryStatus status, String error, LocalDateTime now) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setTenantId(notification.getTenantId());
        delivery.setNotificationId(notification.getId());
        delivery.setRecipientId(notification.getRecipientId());
        delivery.setNotificationType(notification.getType());
        delivery.setChannel(channel);
        delivery.setDestination(destination);
        delivery.setDeliverySecret(secret);
        NotificationTemplateService.RenderedTemplate rendered = templateService.render(
                notification.getTenantId(), channel, notification);
        delivery.setTemplateVersionId(rendered.versionId());
        delivery.setRenderedTitle(rendered.title());
        delivery.setRenderedContent(rendered.content());
        delivery.setStatus(status);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(now);
        delivery.setLastError(error);
        delivery.setDeliveredAt(status == NotificationDeliveryStatus.DELIVERED ? now : null);
        delivery.setCreatedAt(now);
        delivery.setUpdatedAt(now);
        try {
            deliveryMapper.insert(delivery);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Notification {} already has a {} delivery", notification.getId(), channel);
            return false;
        }
    }

    private NotificationDeliveryStatus destinationStatus(String destination) {
        return trim(destination) == null ? NotificationDeliveryStatus.SKIPPED : NotificationDeliveryStatus.PENDING;
    }

    private String missingDestination(String destination, String label) {
        return trim(destination) == null ? label + "未配置" : null;
    }

    private Map<Long, Notification> notificationMap(List<NotificationDelivery> deliveries) {
        List<Long> ids = deliveries.stream().map(NotificationDelivery::getNotificationId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, Notification> result = new LinkedHashMap<>();
        notificationMapper.selectBatchIds(ids).forEach(item -> result.put(item.getId(), item));
        return result;
    }

    private DeliveryView toView(NotificationDelivery delivery, Notification notification) {
        return new DeliveryView(delivery.getId(), delivery.getNotificationId(), delivery.getRecipientId(),
                delivery.getNotificationType(), delivery.getRenderedTitle() == null
                        ? (notification == null ? null : notification.getTitle()) : delivery.getRenderedTitle(),
                delivery.getChannel(), destinationPreview(delivery.getChannel(), delivery.getDestination()),
                delivery.getStatus(), delivery.getAttemptCount(), delivery.getNextAttemptAt(),
                delivery.getResponseStatus(), delivery.getLastError(), delivery.getDeliveredAt(),
                delivery.getTemplateVersionId(), delivery.getCreatedAt(), delivery.getUpdatedAt());
    }

    private String destinationPreview(NotificationChannel channel, String value) {
        if (value == null || value.isBlank()) return null;
        if (channel == NotificationChannel.EMAIL) {
            int separator = value.indexOf('@');
            if (separator <= 1) return "***" + value.substring(Math.max(0, separator));
            return value.substring(0, 1) + "***" + value.substring(separator);
        }
        if (channel == NotificationChannel.WEBHOOK || channel == NotificationChannel.WECOM) {
            try {
                URI uri = URI.create(value);
                return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                        + (uri.getPath() == null ? "" : uri.getPath());
            } catch (IllegalArgumentException ignored) {
                return channel == NotificationChannel.WECOM ? "企业微信机器人已配置" : "Webhook 地址已配置";
            }
        }
        return value;
    }

    private long count(Long tenantId, NotificationDeliveryStatus status) {
        return deliveryMapper.selectCount(new LambdaQueryWrapper<NotificationDelivery>()
                .eq(NotificationDelivery::getTenantId, tenantId)
                .eq(NotificationDelivery::getStatus, status));
    }

    private long countAll(NotificationDeliveryStatus status) {
        return deliveryMapper.selectCount(new LambdaQueryWrapper<NotificationDelivery>()
                .eq(NotificationDelivery::getStatus, status));
    }

    private long backoffSeconds(int attemptCount) {
        return Math.min(30L * (1L << Math.min(6, Math.max(0, attemptCount - 1))), 3600L);
    }

    private String safeError(Exception failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isBlank() ? null : result;
    }

    private record DeliveryAttempt(NotificationDelivery delivery, Notification notification, int attemptCount) { }

    public record DeliveryView(Long id, Long notificationId, Long recipientId, String notificationType,
                               String title, NotificationChannel channel, String destinationPreview,
                               NotificationDeliveryStatus status, Integer attemptCount, LocalDateTime nextAttemptAt,
                               Integer responseStatus, String lastError, LocalDateTime deliveredAt,
                               Long templateVersionId, LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record DeliverySummary(long pending, long processing, long retrying, long delivered,
                                  long dead, long skipped, LocalDateTime oldestPendingAt) { }
}
