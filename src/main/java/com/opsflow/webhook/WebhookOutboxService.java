package com.opsflow.webhook;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class WebhookOutboxService {
    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxService.class);
    private static final int MAX_ATTEMPTS = 8;
    private static final long PROCESSING_LEASE_SECONDS = 90L;

    private final WebhookOutboxMapper outboxMapper;
    private final WebhookEndpointMapper endpointMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    public WebhookOutboxService(WebhookOutboxMapper outboxMapper,
                                WebhookEndpointMapper endpointMapper,
                                WebhookDeliveryMapper deliveryMapper,
                                AuditLogService auditLogService,
                                PlatformTransactionManager transactionManager) {
        this.outboxMapper = outboxMapper;
        this.endpointMapper = endpointMapper;
        this.deliveryMapper = deliveryMapper;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int dispatchPending(int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 200));
        LocalDateTime now = LocalDateTime.now();
        List<WebhookOutboxEvent> due = outboxMapper.selectList(new LambdaQueryWrapper<WebhookOutboxEvent>()
                .in(WebhookOutboxEvent::getStatus, List.of(WebhookOutboxStatus.PENDING,
                        WebhookOutboxStatus.FAILED, WebhookOutboxStatus.PROCESSING))
                .le(WebhookOutboxEvent::getNextAttemptAt, now)
                .orderByAsc(WebhookOutboxEvent::getNextAttemptAt)
                .orderByAsc(WebhookOutboxEvent::getId)
                .last("LIMIT " + safeBatchSize));
        int published = 0;
        for (WebhookOutboxEvent candidate : due) {
            try {
                Boolean completed = transactionTemplate.execute(status -> materialize(candidate.getId()));
                if (Boolean.TRUE.equals(completed)) published++;
            } catch (Exception ex) {
                log.warn("Webhook outbox event {} materialization failed", candidate.getId(), ex);
                try {
                    transactionTemplate.executeWithoutResult(status -> markFailure(candidate.getId(), ex));
                } catch (Exception markFailure) {
                    log.error("Unable to record failure for webhook outbox event {}", candidate.getId(), markFailure);
                }
            }
        }
        return published;
    }

    public List<OutboxEventView> list(Long tenantId, WebhookOutboxStatus status, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<WebhookOutboxEvent> query = new LambdaQueryWrapper<WebhookOutboxEvent>()
                .eq(WebhookOutboxEvent::getTenantId, tenantId)
                .orderByDesc(WebhookOutboxEvent::getCreatedAt)
                .orderByDesc(WebhookOutboxEvent::getId)
                .last("LIMIT " + safeLimit);
        if (status != null) query.eq(WebhookOutboxEvent::getStatus, status);
        return outboxMapper.selectList(query).stream().map(this::toView).toList();
    }

    public int purgePublished(Integer retentionDays) {
        int safeRetentionDays = retentionDays == null ? 30 : Math.max(7, Math.min(retentionDays, 365));
        return outboxMapper.delete(new LambdaQueryWrapper<WebhookOutboxEvent>()
                .eq(WebhookOutboxEvent::getStatus, WebhookOutboxStatus.PUBLISHED)
                .lt(WebhookOutboxEvent::getPublishedAt, LocalDateTime.now().minusDays(safeRetentionDays)));
    }

    public OutboxSummary summary(Long tenantId) {
        long pending = count(tenantId, WebhookOutboxStatus.PENDING);
        long processing = count(tenantId, WebhookOutboxStatus.PROCESSING);
        long failed = count(tenantId, WebhookOutboxStatus.FAILED);
        long dead = count(tenantId, WebhookOutboxStatus.DEAD);
        long published = count(tenantId, WebhookOutboxStatus.PUBLISHED);
        WebhookOutboxEvent oldest = outboxMapper.selectOne(new LambdaQueryWrapper<WebhookOutboxEvent>()
                .eq(WebhookOutboxEvent::getTenantId, tenantId)
                .in(WebhookOutboxEvent::getStatus, List.of(WebhookOutboxStatus.PENDING,
                        WebhookOutboxStatus.PROCESSING, WebhookOutboxStatus.FAILED))
                .orderByAsc(WebhookOutboxEvent::getCreatedAt)
                .last("LIMIT 1"));
        return new OutboxSummary(pending, processing, failed, dead, published,
                oldest == null ? null : oldest.getCreatedAt());
    }

    @Transactional
    public WebhookOutboxEvent retry(Long id, Long tenantId, Long actorId) {
        WebhookOutboxEvent event = outboxMapper.selectForUpdateInTenant(id, tenantId);
        if (event == null) throw new BusinessException("Outbox 事件不存在");
        if (event.getStatus() == WebhookOutboxStatus.PUBLISHED) {
            throw new BusinessException("已发布的 Outbox 事件无需重试");
        }
        LocalDateTime now = LocalDateTime.now();
        event.setStatus(WebhookOutboxStatus.PENDING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setClaimedAt(null);
        event.setLastError(null);
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
        auditLogService.record(tenantId, actorId, "WEBHOOK_OUTBOX", id, "WEBHOOK_OUTBOX_RETRY",
                null, event, "手动重试事务消息 Outbox 事件");
        return event;
    }

    private boolean materialize(Long id) {
        WebhookOutboxEvent event = outboxMapper.selectForUpdate(id);
        if (event == null || event.getStatus() == WebhookOutboxStatus.PUBLISHED
                || event.getStatus() == WebhookOutboxStatus.DEAD) return false;
        LocalDateTime now = LocalDateTime.now();
        if (event.getNextAttemptAt() != null && event.getNextAttemptAt().isAfter(now)) return false;

        int attempt = (event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1;
        event.setStatus(WebhookOutboxStatus.PROCESSING);
        event.setAttemptCount(attempt);
        event.setClaimedAt(now);
        event.setNextAttemptAt(now.plusSeconds(PROCESSING_LEASE_SECONDS));
        event.setLastError(null);
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);

        List<WebhookEndpoint> endpoints = endpointMapper.selectList(new LambdaQueryWrapper<WebhookEndpoint>()
                .eq(WebhookEndpoint::getTenantId, event.getTenantId())
                .eq(WebhookEndpoint::getEnabled, true));
        for (WebhookEndpoint endpoint : endpoints) {
            if (!matches(endpoint.getEventTypes(), event.getEventType())) continue;
            WebhookDelivery delivery = delivery(event, endpoint, now);
            try {
                deliveryMapper.insert(delivery);
            } catch (DataIntegrityViolationException duplicate) {
                log.debug("Webhook delivery already materialized for endpoint {} and event {}",
                        endpoint.getId(), event.getEventId());
            }
        }

        event.setStatus(WebhookOutboxStatus.PUBLISHED);
        event.setPublishedAt(now);
        event.setNextAttemptAt(now);
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
        return true;
    }

    private void markFailure(Long id, Exception failure) {
        WebhookOutboxEvent event = outboxMapper.selectForUpdate(id);
        if (event == null || event.getStatus() == WebhookOutboxStatus.PUBLISHED
                || event.getStatus() == WebhookOutboxStatus.DEAD) return;
        int attempt = (event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1;
        LocalDateTime now = LocalDateTime.now();
        boolean dead = attempt >= MAX_ATTEMPTS;
        event.setAttemptCount(attempt);
        event.setStatus(dead ? WebhookOutboxStatus.DEAD : WebhookOutboxStatus.FAILED);
        event.setLastError(safeError(failure));
        event.setClaimedAt(null);
        event.setNextAttemptAt(now.plusSeconds(backoffSeconds(attempt)));
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
    }

    private WebhookDelivery delivery(WebhookOutboxEvent event, WebhookEndpoint endpoint, LocalDateTime now) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setTenantId(event.getTenantId());
        delivery.setEndpointId(endpoint.getId());
        delivery.setEventId(event.getEventId());
        delivery.setEventType(event.getEventType());
        delivery.setResourceType(event.getResourceType());
        delivery.setResourceId(event.getResourceId());
        delivery.setPayloadJson(event.getPayloadJson());
        delivery.setSignature(WebhookEventPublisher.sign(endpoint.getSecret(), event.getPayloadJson()));
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(now);
        delivery.setCreatedAt(now);
        delivery.setUpdatedAt(now);
        return delivery;
    }

    private long count(Long tenantId, WebhookOutboxStatus status) {
        return outboxMapper.selectCount(new LambdaQueryWrapper<WebhookOutboxEvent>()
                .eq(WebhookOutboxEvent::getTenantId, tenantId)
                .eq(WebhookOutboxEvent::getStatus, status));
    }

    private OutboxEventView toView(WebhookOutboxEvent event) {
        return new OutboxEventView(event.getId(), event.getAuditLogId(), event.getEventId(), event.getEventType(),
                event.getActorId(), event.getResourceType(), event.getResourceId(), event.getStatus(),
                event.getAttemptCount(), event.getNextAttemptAt(), event.getClaimedAt(), event.getPublishedAt(),
                event.getLastError(), event.getCreatedAt(), event.getUpdatedAt());
    }

    private boolean matches(String configured, String eventType) {
        if (configured == null || configured.isBlank() || eventType == null) return false;
        for (String raw : configured.split(",")) {
            String value = raw.trim().toUpperCase(Locale.ROOT);
            if ("*".equals(value) || eventType.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private long backoffSeconds(int attempt) {
        long seconds = 15L * (1L << Math.min(8, Math.max(0, attempt - 1)));
        return Math.min(seconds, 3600L);
    }

    private String safeError(Exception failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName() + ": "
                + (message == null || message.isBlank() ? "无错误详情" : message);
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public record OutboxSummary(long pending, long processing, long failed, long dead,
                                long published, LocalDateTime oldestPendingAt) { }

    public record OutboxEventView(Long id, Long auditLogId, String eventId, String eventType, Long actorId,
                                  String resourceType, Long resourceId, WebhookOutboxStatus status,
                                  Integer attemptCount, LocalDateTime nextAttemptAt, LocalDateTime claimedAt,
                                  LocalDateTime publishedAt, String lastError,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
