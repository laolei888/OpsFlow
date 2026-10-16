package com.opsflow.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class AsyncMessageOutboxService {
    private static final Logger log = LoggerFactory.getLogger(AsyncMessageOutboxService.class);
    private static final int MAX_ATTEMPTS = 8;
    private static final long PROCESSING_LEASE_SECONDS = 60L;

    private final AsyncMessageOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final AuditLogService auditLogService;
    private final AsyncMessagingMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Duration confirmTimeout;

    public AsyncMessageOutboxService(AsyncMessageOutboxMapper outboxMapper,
                                     RabbitTemplate rabbitTemplate,
                                     AuditLogService auditLogService,
                                     AsyncMessagingMetrics metrics,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${opsflow.messaging.publish-confirm-timeout:5s}") Duration confirmTimeout) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitTemplate.setMandatory(true);
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.confirmTimeout = confirmTimeout;
    }

    public int dispatchPending(int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 200));
        LocalDateTime now = LocalDateTime.now();
        List<AsyncMessageOutboxEvent> due = outboxMapper.selectList(
                new LambdaQueryWrapper<AsyncMessageOutboxEvent>()
                        .in(AsyncMessageOutboxEvent::getStatus, List.of(
                                AsyncMessageOutboxStatus.PENDING,
                                AsyncMessageOutboxStatus.FAILED,
                                AsyncMessageOutboxStatus.PROCESSING))
                        .le(AsyncMessageOutboxEvent::getNextAttemptAt, now)
                        .orderByAsc(AsyncMessageOutboxEvent::getNextAttemptAt)
                        .orderByAsc(AsyncMessageOutboxEvent::getId)
                        .last("LIMIT " + safeBatchSize));
        int published = 0;
        for (AsyncMessageOutboxEvent candidate : due) {
            try {
                PublishAttempt attempt = transactionTemplate.execute(status -> claim(candidate.getId()));
                if (attempt == null) continue;
                publish(attempt);
                transactionTemplate.executeWithoutResult(status -> markPublished(attempt.id()));
                metrics.published();
                published++;
            } catch (Exception ex) {
                log.warn("Asynchronous outbox message {} publish failed", candidate.getMessageId(), ex);
                try {
                    transactionTemplate.executeWithoutResult(status -> markFailure(candidate.getId(), ex));
                } catch (Exception markFailure) {
                    log.error("Unable to record asynchronous outbox failure for {}",
                            candidate.getMessageId(), markFailure);
                }
            }
        }
        try {
            refreshMetrics();
        } catch (Exception ex) {
            log.warn("Unable to refresh asynchronous message backlog metrics", ex);
        }
        return published;
    }

    public List<OutboxEventView> list(Long tenantId, AsyncMessageOutboxStatus status,
                                      String notificationType, String aggregateType,
                                      String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<AsyncMessageOutboxEvent> query = new LambdaQueryWrapper<AsyncMessageOutboxEvent>()
                .eq(AsyncMessageOutboxEvent::getTenantId, tenantId)
                .orderByDesc(AsyncMessageOutboxEvent::getCreatedAt)
                .orderByDesc(AsyncMessageOutboxEvent::getId)
                .last("LIMIT " + safeLimit);
        if (status != null) query.eq(AsyncMessageOutboxEvent::getStatus, status);
        if (notificationType != null && !notificationType.isBlank()) {
            query.eq(AsyncMessageOutboxEvent::getNotificationType,
                    notificationType.trim().toUpperCase(Locale.ROOT));
        }
        if (aggregateType != null && !aggregateType.isBlank()) {
            query.eq(AsyncMessageOutboxEvent::getAggregateType,
                    aggregateType.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(nested -> nested.like(AsyncMessageOutboxEvent::getMessageId, search)
                    .or().like(AsyncMessageOutboxEvent::getTitle, search)
                    .or().like(AsyncMessageOutboxEvent::getDedupeKey, search));
        }
        return outboxMapper.selectList(query).stream().map(this::toView).toList();
    }

    public OutboxSummary summary(Long tenantId) {
        long pending = count(tenantId, AsyncMessageOutboxStatus.PENDING);
        long processing = count(tenantId, AsyncMessageOutboxStatus.PROCESSING);
        long failed = count(tenantId, AsyncMessageOutboxStatus.FAILED);
        long dead = count(tenantId, AsyncMessageOutboxStatus.DEAD);
        long published = count(tenantId, AsyncMessageOutboxStatus.PUBLISHED);
        AsyncMessageOutboxEvent oldest = outboxMapper.selectOne(
                new LambdaQueryWrapper<AsyncMessageOutboxEvent>()
                        .eq(AsyncMessageOutboxEvent::getTenantId, tenantId)
                        .in(AsyncMessageOutboxEvent::getStatus, List.of(
                                AsyncMessageOutboxStatus.PENDING,
                                AsyncMessageOutboxStatus.PROCESSING,
                                AsyncMessageOutboxStatus.FAILED))
                        .orderByAsc(AsyncMessageOutboxEvent::getCreatedAt)
                        .last("LIMIT 1"));
        return new OutboxSummary(pending, processing, failed, dead, published,
                oldest == null ? null : oldest.getCreatedAt());
    }

    public OutboxDiagnostic diagnostic(Long id, Long tenantId) {
        AsyncMessageOutboxEvent event = outboxMapper.selectOne(
                new LambdaQueryWrapper<AsyncMessageOutboxEvent>()
                        .eq(AsyncMessageOutboxEvent::getId, id)
                        .eq(AsyncMessageOutboxEvent::getTenantId, tenantId));
        if (event == null) throw new BusinessException("异步消息不存在");
        String category = failureCategory(event);
        boolean retryable = event.getStatus() == AsyncMessageOutboxStatus.FAILED
                || event.getStatus() == AsyncMessageOutboxStatus.DEAD;
        int attempts = event.getAttemptCount() == null ? 0 : event.getAttemptCount();
        int automaticAttemptsRemaining = event.getStatus() == AsyncMessageOutboxStatus.FAILED
                ? Math.max(0, MAX_ATTEMPTS - attempts) : 0;
        long ageSeconds = event.getCreatedAt() == null ? 0
                : Math.max(0, Duration.between(event.getCreatedAt(), LocalDateTime.now()).toSeconds());
        return new OutboxDiagnostic(toView(event), event.getRoutingKey(),
                truncate(event.getPayloadJson() == null ? "" : event.getPayloadJson(), 4000),
                category, recommendation(category, event.getStatus()), retryable,
                automaticAttemptsRemaining, ageSeconds);
    }

    @Transactional
    public AsyncMessageOutboxEvent retry(Long id, Long tenantId, Long actorId) {
        AsyncMessageOutboxEvent event = outboxMapper.selectForUpdateInTenant(id, tenantId);
        if (event == null) throw new BusinessException("异步消息不存在");
        if (event.getStatus() == AsyncMessageOutboxStatus.PUBLISHED) {
            throw new BusinessException("已发布的异步消息无需重试");
        }
        if (event.getStatus() == AsyncMessageOutboxStatus.PENDING
                || event.getStatus() == AsyncMessageOutboxStatus.PROCESSING) {
            throw new BusinessException("消息正在等待处理，无需重复重试");
        }
        LocalDateTime now = LocalDateTime.now();
        event.setStatus(AsyncMessageOutboxStatus.PENDING);
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setClaimedAt(null);
        event.setLastError(null);
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
        auditLogService.record(tenantId, actorId, "ASYNC_MESSAGE", id, "ASYNC_MESSAGE_RETRY",
                null, event, "手动重试异步消息 Outbox 事件");
        return event;
    }

    @Transactional
    public int retryBatch(List<Long> ids, Long tenantId, Long actorId) {
        if (ids == null || ids.isEmpty()) throw new BusinessException("至少选择一条失败消息");
        Set<Long> distinctIds = new LinkedHashSet<>(ids);
        if (distinctIds.size() > 100) throw new BusinessException("单次最多重试 100 条消息");
        int retried = 0;
        for (Long id : distinctIds) {
            if (id == null) continue;
            retry(id, tenantId, actorId);
            retried++;
        }
        if (retried == 0) throw new BusinessException("至少选择一条有效消息");
        return retried;
    }

    public int purgePublished(Integer retentionDays) {
        int safeRetentionDays = retentionDays == null ? 30 : Math.max(7, Math.min(retentionDays, 365));
        return outboxMapper.delete(new LambdaQueryWrapper<AsyncMessageOutboxEvent>()
                .eq(AsyncMessageOutboxEvent::getStatus, AsyncMessageOutboxStatus.PUBLISHED)
                .lt(AsyncMessageOutboxEvent::getPublishedAt, LocalDateTime.now().minusDays(safeRetentionDays)));
    }

    public void refreshMetrics() {
        metrics.updateBacklog(count(null, AsyncMessageOutboxStatus.PENDING),
                count(null, AsyncMessageOutboxStatus.FAILED),
                count(null, AsyncMessageOutboxStatus.DEAD));
    }

    private PublishAttempt claim(Long id) {
        AsyncMessageOutboxEvent event = outboxMapper.selectForUpdate(id);
        if (event == null || event.getStatus() == AsyncMessageOutboxStatus.PUBLISHED
                || event.getStatus() == AsyncMessageOutboxStatus.DEAD) return null;
        LocalDateTime now = LocalDateTime.now();
        if (event.getNextAttemptAt() != null && event.getNextAttemptAt().isAfter(now)) return null;

        int attempt = (event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1;
        event.setStatus(AsyncMessageOutboxStatus.PROCESSING);
        event.setAttemptCount(attempt);
        event.setClaimedAt(now);
        event.setNextAttemptAt(now.plusSeconds(PROCESSING_LEASE_SECONDS));
        event.setLastError(null);
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
        return new PublishAttempt(event.getId(), event.getMessageId(), event.getEventType(),
                event.getRoutingKey(), event.getTenantId(), event.getPayloadJson(), attempt);
    }

    private void publish(PublishAttempt attempt) throws Exception {
        CorrelationData correlation = new CorrelationData(attempt.messageId());
        rabbitTemplate.convertAndSend(MessagingTopology.EXCHANGE, attempt.routingKey(), attempt.payloadJson(),
                message -> {
                    message.getMessageProperties().setMessageId(attempt.messageId());
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setHeader("opsflow-event-type", attempt.eventType());
                    message.getMessageProperties().setHeader("opsflow-tenant-id", attempt.tenantId());
                    message.getMessageProperties().setHeader("opsflow-publish-attempt", attempt.attempt());
                    return message;
                }, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.ack()) {
            throw new IllegalStateException("RabbitMQ rejected message: " + safeMessage(confirm.reason()));
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ returned unroutable message: "
                    + correlation.getReturned().getReplyText());
        }
    }

    private void markPublished(Long id) {
        AsyncMessageOutboxEvent event = outboxMapper.selectForUpdate(id);
        if (event == null || event.getStatus() == AsyncMessageOutboxStatus.PUBLISHED) return;
        LocalDateTime now = LocalDateTime.now();
        event.setStatus(AsyncMessageOutboxStatus.PUBLISHED);
        event.setPublishedAt(now);
        event.setNextAttemptAt(now);
        event.setClaimedAt(null);
        event.setLastError(null);
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
    }

    private void markFailure(Long id, Exception failure) {
        AsyncMessageOutboxEvent event = outboxMapper.selectForUpdate(id);
        if (event == null || event.getStatus() == AsyncMessageOutboxStatus.PUBLISHED
                || event.getStatus() == AsyncMessageOutboxStatus.DEAD) return;
        int attempt = Math.max(1, event.getAttemptCount() == null ? 0 : event.getAttemptCount());
        LocalDateTime now = LocalDateTime.now();
        boolean dead = attempt >= MAX_ATTEMPTS;
        event.setStatus(dead ? AsyncMessageOutboxStatus.DEAD : AsyncMessageOutboxStatus.FAILED);
        event.setLastError(safeError(failure));
        event.setClaimedAt(null);
        event.setNextAttemptAt(now.plusSeconds(backoffSeconds(attempt)));
        event.setUpdatedAt(now);
        outboxMapper.updateById(event);
    }

    private long count(Long tenantId, AsyncMessageOutboxStatus status) {
        LambdaQueryWrapper<AsyncMessageOutboxEvent> query = new LambdaQueryWrapper<AsyncMessageOutboxEvent>()
                .eq(AsyncMessageOutboxEvent::getStatus, status);
        if (tenantId != null) query.eq(AsyncMessageOutboxEvent::getTenantId, tenantId);
        return outboxMapper.selectCount(query);
    }

    private OutboxEventView toView(AsyncMessageOutboxEvent event) {
        return new OutboxEventView(event.getId(), event.getMessageId(), event.getEventType(),
                event.getNotificationType(), event.getTitle(),
                event.getAggregateType(), event.getAggregateId(), event.getRecipientId(), event.getDedupeKey(),
                event.getStatus(), event.getAttemptCount(), event.getNextAttemptAt(), event.getClaimedAt(),
                event.getPublishedAt(), event.getLastError(), event.getCreatedAt(), event.getUpdatedAt());
    }

    private long backoffSeconds(int attempt) {
        long seconds = 5L * (1L << Math.min(8, Math.max(0, attempt - 1)));
        return Math.min(seconds, 1800L);
    }

    private String safeError(Exception failure) {
        return truncate(failure.getClass().getSimpleName() + ": " + safeMessage(failure.getMessage()), 1000);
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "无错误详情" : message;
    }

    private String failureCategory(AsyncMessageOutboxEvent event) {
        if (event.getStatus() == AsyncMessageOutboxStatus.PUBLISHED) return "DELIVERED";
        if (event.getStatus() == AsyncMessageOutboxStatus.PENDING) return "QUEUED";
        if (event.getStatus() == AsyncMessageOutboxStatus.PROCESSING) return "PUBLISHING";
        String error = event.getLastError() == null ? "" : event.getLastError().toLowerCase(Locale.ROOT);
        if (error.contains("unroutable") || error.contains("no_route") || error.contains("returned")) {
            return "ROUTING";
        }
        if (error.contains("timeout") || error.contains("timed out")) return "BROKER_TIMEOUT";
        if (error.contains("connection") || error.contains("connect") || error.contains("unavailable")
                || error.contains("refused")) return "BROKER_UNAVAILABLE";
        if (error.contains("rejected") || error.contains("nack")) return "BROKER_REJECTED";
        if (error.contains("serializ") || error.contains("json") || error.contains("payload")) return "PAYLOAD";
        return "PUBLISH_FAILURE";
    }

    private String recommendation(String category, AsyncMessageOutboxStatus status) {
        return switch (category) {
            case "DELIVERED" -> "消息已获得 Broker Confirm，无需处理。";
            case "QUEUED" -> "消息正在等待调度器发布，请观察下一次派发。";
            case "PUBLISHING" -> "消息已被工作线程领取；超过处理租约后会自动恢复。";
            case "ROUTING" -> "检查 RabbitMQ 交换机、绑定键和消费者队列后再执行重放。";
            case "BROKER_TIMEOUT" -> "检查 RabbitMQ 延迟与网络状况；恢复后可安全重放。";
            case "BROKER_UNAVAILABLE" -> "确认 RabbitMQ 服务和连接配置恢复后再执行重放。";
            case "BROKER_REJECTED" -> "检查 Broker Confirm 拒绝原因、权限与资源限制后重放。";
            case "PAYLOAD" -> "检查消息负载格式和字段长度；修复生产逻辑后再重放。";
            default -> status == AsyncMessageOutboxStatus.DEAD
                    ? "消息已耗尽自动重试，请结合错误详情排查后手动重放。"
                    : "消息会按指数退避自动重试，也可在问题恢复后手动重放。";
        };
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record PublishAttempt(Long id, String messageId, String eventType, String routingKey,
                                  Long tenantId, String payloadJson, int attempt) { }

    public record OutboxSummary(long pending, long processing, long failed, long dead,
                                long published, LocalDateTime oldestPendingAt) { }

    public record OutboxDiagnostic(OutboxEventView event, String routingKey, String payloadJson,
                                   String failureCategory, String recommendation, boolean retryable,
                                   int automaticAttemptsRemaining, long ageSeconds) { }

    public record OutboxEventView(Long id, String messageId, String eventType,
                                  String notificationType, String title, String aggregateType,
                                  Long aggregateId, Long recipientId, String dedupeKey,
                                  AsyncMessageOutboxStatus status, Integer attemptCount,
                                  LocalDateTime nextAttemptAt, LocalDateTime claimedAt,
                                  LocalDateTime publishedAt, String lastError,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
