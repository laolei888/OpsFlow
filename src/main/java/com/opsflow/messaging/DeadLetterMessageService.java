package com.opsflow.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DeadLetterMessageService {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterMessageService.class);
    private static final String CSV_BOM = "\ufeff";
    private static final long REQUEUE_LEASE_SECONDS = 60L;

    private final DeadLetterMessageMapper deadLetterMapper;
    private final AsyncMessageOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final AuditLogService auditLogService;
    private final AsyncMessagingMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Duration confirmTimeout;

    public DeadLetterMessageService(DeadLetterMessageMapper deadLetterMapper,
                                    AsyncMessageOutboxMapper outboxMapper,
                                    RabbitTemplate rabbitTemplate,
                                    AuditLogService auditLogService,
                                    AsyncMessagingMetrics metrics,
                                    ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${opsflow.messaging.publish-confirm-timeout:5s}") Duration confirmTimeout) {
        this.deadLetterMapper = deadLetterMapper;
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitTemplate.setMandatory(true);
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.confirmTimeout = confirmTimeout;
    }

    public void quarantine(Message brokerMessage) {
        String payload = new String(brokerMessage.getBody(), StandardCharsets.UTF_8);
        JsonNode json = parsePayload(payload);
        Long tenantId = longHeader(brokerMessage, "opsflow-tenant-id");
        if (tenantId == null) tenantId = longField(json, "tenantId");
        if (tenantId == null) throw new IllegalArgumentException("Dead-letter message has no tenant identity");

        String messageId = value(brokerMessage.getMessageProperties().getMessageId());
        if (messageId == null) messageId = textField(json, "messageId");
        if (messageId == null) messageId = UUID.nameUUIDFromBytes(brokerMessage.getBody()).toString();

        DeadLetterMessage record = deadLetterMapper.selectForUpdateByMessageId(tenantId, messageId);
        boolean repeatedFailureCycle = record != null && record.getStatus() == DeadLetterStatus.REQUEUED;
        LocalDateTime now = LocalDateTime.now();
        if (record == null) {
            record = new DeadLetterMessage();
            record.setTenantId(tenantId);
            record.setMessageId(messageId);
            record.setRequeueCount(0);
            record.setQuarantinedAt(now);
        }

        AsyncMessageOutboxEvent outbox = findOutbox(tenantId, messageId);
        record.setOutboxEventId(outbox == null ? record.getOutboxEventId() : outbox.getId());
        record.setNotificationType(firstNonBlank(textField(json, "type"),
                outbox == null ? null : outbox.getNotificationType()));
        record.setTitle(firstNonBlank(textField(json, "title"), outbox == null ? null : outbox.getTitle()));
        record.setRecipientId(firstNonNull(longField(json, "recipientId"),
                outbox == null ? null : outbox.getRecipientId()));
        record.setDedupeKey(firstNonBlank(textField(json, "dedupeKey"),
                outbox == null ? null : outbox.getDedupeKey()));
        record.setSourceQueue(header(brokerMessage, "x-first-death-queue", MessagingTopology.NOTIFICATION_QUEUE));
        record.setSourceExchange(header(brokerMessage, "x-first-death-exchange", MessagingTopology.EXCHANGE));
        record.setOriginalRoutingKey(originalRoutingKey(brokerMessage));
        record.setDeadLetterReason(header(brokerMessage, "x-first-death-reason", "rejected"));
        long brokerDeathCount = deathCount(brokerMessage);
        record.setDeathCount(repeatedFailureCycle
                ? (record.getDeathCount() == null ? 0 : record.getDeathCount()) + brokerDeathCount
                : Math.max(record.getDeathCount() == null ? 0 : record.getDeathCount(), brokerDeathCount));
        record.setPayloadJson(payload);
        record.setHeadersJson(headersJson(brokerMessage));
        record.setStatus(DeadLetterStatus.QUARANTINED);
        record.setLastError(null);
        record.setResolutionNote(null);
        record.setRequeueStartedAt(null);
        record.setResolvedAt(null);
        record.setQuarantinedAt(now);
        record.setUpdatedAt(now);
        if (record.getId() == null) deadLetterMapper.insert(record);
        else deadLetterMapper.updateById(record);
        metrics.deadLetterQuarantined();
        refreshMetrics(tenantId);
    }

    public List<DeadLetterView> list(Long tenantId, DeadLetterStatus status, String keyword, Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<DeadLetterMessage> query = new LambdaQueryWrapper<DeadLetterMessage>()
                .eq(DeadLetterMessage::getTenantId, tenantId)
                .orderByDesc(DeadLetterMessage::getQuarantinedAt)
                .orderByDesc(DeadLetterMessage::getId)
                .last("LIMIT " + safeLimit);
        if (status != null) query.eq(DeadLetterMessage::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            query.and(nested -> nested.like(DeadLetterMessage::getMessageId, search)
                    .or().like(DeadLetterMessage::getTitle, search)
                    .or().like(DeadLetterMessage::getDedupeKey, search));
        }
        return deadLetterMapper.selectList(query).stream().map(this::toView).toList();
    }

    public DeadLetterSummary summary(Long tenantId) {
        long quarantined = count(tenantId, DeadLetterStatus.QUARANTINED);
        long requeueing = count(tenantId, DeadLetterStatus.REQUEUEING);
        long requeued = count(tenantId, DeadLetterStatus.REQUEUED);
        long discarded = count(tenantId, DeadLetterStatus.DISCARDED);
        DeadLetterMessage oldest = deadLetterMapper.selectOne(new LambdaQueryWrapper<DeadLetterMessage>()
                .eq(DeadLetterMessage::getTenantId, tenantId)
                .in(DeadLetterMessage::getStatus, List.of(DeadLetterStatus.QUARANTINED,
                        DeadLetterStatus.REQUEUEING))
                .orderByAsc(DeadLetterMessage::getQuarantinedAt)
                .last("LIMIT 1"));
        return new DeadLetterSummary(quarantined, requeueing, requeued, discarded,
                oldest == null ? null : oldest.getQuarantinedAt());
    }

    public DeadLetterDetail detail(Long id, Long tenantId) {
        DeadLetterMessage message = find(id, tenantId);
        return new DeadLetterDetail(toView(message), message.getPayloadJson(), message.getHeadersJson(),
                recommendation(message), message.getResolutionNote(), message.getLastError());
    }

    public DeadLetterMessage requeue(Long id, Long tenantId, Long actorId,
                                     String expectedMessageId, String reason) {
        String safeReason = requireReason(reason);
        RequeueAttempt attempt = transactionTemplate.execute(status ->
                claimForRequeue(id, tenantId, expectedMessageId));
        if (attempt == null) throw new BusinessException("死信消息不存在");
        try {
            publish(attempt);
            DeadLetterMessage requeued = transactionTemplate.execute(status -> markRequeued(attempt.id(), tenantId,
                    safeReason));
            recordAuditSafely(tenantId, actorId, id, "DEAD_LETTER_REQUEUED", requeued,
                    "死信消息已受控重新入队，原因：" + safeReason);
            metrics.deadLetterRequeued();
            refreshMetrics(tenantId);
            return requeued;
        } catch (BusinessException ex) {
            restoreAfterFailure(attempt.id(), tenantId, ex);
            throw ex;
        } catch (Exception ex) {
            restoreAfterFailure(attempt.id(), tenantId, ex);
            throw new BusinessException("重新入队失败：" + safeMessage(ex.getMessage()));
        }
    }

    public DeadLetterMessage discard(Long id, Long tenantId, Long actorId,
                                     String expectedMessageId, String reason) {
        String safeReason = requireReason(reason);
        DeadLetterMessage discarded = transactionTemplate.execute(status -> {
            DeadLetterMessage message = deadLetterMapper.selectForUpdateInTenant(id, tenantId);
            validateConfirmation(message, expectedMessageId);
            if (message.getStatus() != DeadLetterStatus.QUARANTINED) {
                throw new BusinessException("只有隔离中的死信可以作废");
            }
            LocalDateTime now = LocalDateTime.now();
            message.setStatus(DeadLetterStatus.DISCARDED);
            message.setResolutionNote(safeReason);
            message.setResolvedAt(now);
            message.setUpdatedAt(now);
            deadLetterMapper.updateById(message);
            return message;
        });
        recordAuditSafely(tenantId, actorId, id, "DEAD_LETTER_DISCARDED", discarded,
                "死信消息已人工作废，原因：" + safeReason);
        metrics.deadLetterDiscarded();
        refreshMetrics(tenantId);
        return discarded;
    }

    public byte[] exportCsv(Long tenantId, Long actorId, List<Long> ids) {
        Set<Long> selected = ids == null ? Set.of() : new LinkedHashSet<>(ids);
        if (selected.isEmpty()) throw new BusinessException("请至少选择一条死信消息再导出");
        if (selected.size() > 100) throw new BusinessException("单次最多导出 100 条死信消息");
        List<DeadLetterMessage> messages = deadLetterMapper.selectList(
                new LambdaQueryWrapper<DeadLetterMessage>()
                        .eq(DeadLetterMessage::getTenantId, tenantId)
                        .in(DeadLetterMessage::getId, selected)
                        .orderByDesc(DeadLetterMessage::getQuarantinedAt));
        if (messages.size() != selected.size()) throw new BusinessException("部分死信消息不存在或不属于当前租户");
        StringBuilder csv = new StringBuilder(CSV_BOM);
        appendCsvRow(csv, "记录ID", "消息ID", "通知类型", "标题", "状态", "死亡原因", "死亡次数",
                "重新入队次数", "源队列", "原始路由键", "接收人", "幂等键", "隔离时间", "处置说明",
                "消息负载", "Broker头信息");
        for (DeadLetterMessage message : messages) {
            appendCsvRow(csv, message.getId(), message.getMessageId(), message.getNotificationType(),
                    message.getTitle(), message.getStatus(), message.getDeadLetterReason(), message.getDeathCount(),
                    message.getRequeueCount(), message.getSourceQueue(), message.getOriginalRoutingKey(),
                    message.getRecipientId(), message.getDedupeKey(), message.getQuarantinedAt(),
                    message.getResolutionNote(), message.getPayloadJson(), message.getHeadersJson());
        }
        recordAuditSafely(tenantId, actorId, null, "DEAD_LETTER_EXPORTED", null,
                "导出死信隔离记录 CSV，共 " + messages.size() + " 条");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private RequeueAttempt claimForRequeue(Long id, Long tenantId, String expectedMessageId) {
        DeadLetterMessage message = deadLetterMapper.selectForUpdateInTenant(id, tenantId);
        validateConfirmation(message, expectedMessageId);
        boolean staleClaim = message.getStatus() == DeadLetterStatus.REQUEUEING
                && message.getRequeueStartedAt() != null
                && message.getRequeueStartedAt().isBefore(LocalDateTime.now().minusSeconds(REQUEUE_LEASE_SECONDS));
        if (message.getStatus() != DeadLetterStatus.QUARANTINED && !staleClaim) {
            throw new BusinessException("只有隔离中的死信可以重新入队");
        }
        LocalDateTime now = LocalDateTime.now();
        message.setStatus(DeadLetterStatus.REQUEUEING);
        message.setRequeueStartedAt(now);
        message.setLastError(null);
        message.setUpdatedAt(now);
        deadLetterMapper.updateById(message);
        return new RequeueAttempt(message.getId(), message.getMessageId(), message.getTenantId(),
                message.getNotificationType(), message.getOriginalRoutingKey(), message.getPayloadJson(),
                (message.getRequeueCount() == null ? 0 : message.getRequeueCount()) + 1);
    }

    private void publish(RequeueAttempt attempt) throws Exception {
        CorrelationData correlation = new CorrelationData(attempt.messageId() + ":requeue:" + attempt.requeueCount());
        rabbitTemplate.convertAndSend(MessagingTopology.EXCHANGE, attempt.routingKey(), attempt.payloadJson(),
                message -> {
                    message.getMessageProperties().setMessageId(attempt.messageId());
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setHeader("opsflow-event-type", "NOTIFICATION_REQUESTED");
                    message.getMessageProperties().setHeader("opsflow-tenant-id", attempt.tenantId());
                    message.getMessageProperties().setHeader("opsflow-dead-letter-requeue", attempt.requeueCount());
                    return message;
                }, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.ack()) throw new BusinessException("RabbitMQ 拒绝重新入队：" + safeMessage(confirm.reason()));
        if (correlation.getReturned() != null) {
            throw new BusinessException("RabbitMQ 没有匹配的消息路由：" +
                    safeMessage(correlation.getReturned().getReplyText()));
        }
    }

    private DeadLetterMessage markRequeued(Long id, Long tenantId, String reason) {
        DeadLetterMessage message = deadLetterMapper.selectForUpdateInTenant(id, tenantId);
        if (message == null) throw new BusinessException("死信消息不存在");
        LocalDateTime now = LocalDateTime.now();
        message.setStatus(DeadLetterStatus.REQUEUED);
        message.setRequeueCount((message.getRequeueCount() == null ? 0 : message.getRequeueCount()) + 1);
        message.setResolutionNote(reason);
        message.setLastRequeuedAt(now);
        message.setResolvedAt(now);
        message.setRequeueStartedAt(null);
        message.setLastError(null);
        message.setUpdatedAt(now);
        deadLetterMapper.updateById(message);
        return message;
    }

    private void restoreAfterFailure(Long id, Long tenantId, Exception failure) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                DeadLetterMessage message = deadLetterMapper.selectForUpdateInTenant(id, tenantId);
                if (message == null) return;
                message.setStatus(DeadLetterStatus.QUARANTINED);
                message.setRequeueStartedAt(null);
                message.setLastError(truncate(failure.getClass().getSimpleName() + ": " +
                        safeMessage(failure.getMessage()), 1000));
                message.setUpdatedAt(LocalDateTime.now());
                deadLetterMapper.updateById(message);
            });
        } catch (Exception restoreFailure) {
            log.error("Unable to restore dead-letter message {} after requeue failure", id, restoreFailure);
        }
    }

    private void validateConfirmation(DeadLetterMessage message, String expectedMessageId) {
        if (message == null) throw new BusinessException("死信消息不存在");
        if (expectedMessageId == null || !message.getMessageId().equals(expectedMessageId.trim())) {
            throw new BusinessException("消息 ID 二次确认不匹配");
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5) throw new BusinessException("处置原因至少填写 5 个字符");
        return truncate(reason.trim(), 500);
    }

    private DeadLetterMessage find(Long id, Long tenantId) {
        DeadLetterMessage message = deadLetterMapper.selectOne(new LambdaQueryWrapper<DeadLetterMessage>()
                .eq(DeadLetterMessage::getId, id)
                .eq(DeadLetterMessage::getTenantId, tenantId));
        if (message == null) throw new BusinessException("死信消息不存在");
        return message;
    }

    private AsyncMessageOutboxEvent findOutbox(Long tenantId, String messageId) {
        return outboxMapper.selectOne(new LambdaQueryWrapper<AsyncMessageOutboxEvent>()
                .eq(AsyncMessageOutboxEvent::getTenantId, tenantId)
                .eq(AsyncMessageOutboxEvent::getMessageId, messageId)
                .last("LIMIT 1"));
    }

    private long count(Long tenantId, DeadLetterStatus status) {
        LambdaQueryWrapper<DeadLetterMessage> query = new LambdaQueryWrapper<DeadLetterMessage>()
                .eq(DeadLetterMessage::getStatus, status);
        if (tenantId != null) query.eq(DeadLetterMessage::getTenantId, tenantId);
        return deadLetterMapper.selectCount(query);
    }

    private void refreshMetrics(Long tenantId) {
        try {
            metrics.updateDeadLetterBacklog(count(null, DeadLetterStatus.QUARANTINED),
                    count(null, DeadLetterStatus.REQUEUEING));
        } catch (Exception ex) {
            log.warn("Unable to refresh dead-letter quarantine metrics", ex);
        }
    }

    private void recordAuditSafely(Long tenantId, Long actorId, Long resourceId, String action,
                                   Object after, String detail) {
        try {
            auditLogService.record(tenantId, actorId, "DEAD_LETTER", resourceId, action,
                    null, after, detail);
        } catch (Exception ex) {
            log.error("Unable to record dead-letter governance audit action {} for message {}",
                    action, resourceId, ex);
        }
    }

    private DeadLetterView toView(DeadLetterMessage message) {
        return new DeadLetterView(message.getId(), message.getOutboxEventId(), message.getMessageId(),
                message.getNotificationType(), message.getTitle(), message.getRecipientId(),
                message.getDedupeKey(), message.getSourceQueue(), message.getOriginalRoutingKey(),
                message.getDeadLetterReason(), message.getDeathCount(), message.getStatus(),
                message.getRequeueCount(), message.getQuarantinedAt(), message.getLastRequeuedAt(),
                message.getResolvedAt(), message.getUpdatedAt());
    }

    private String recommendation(DeadLetterMessage message) {
        if (message.getStatus() == DeadLetterStatus.REQUEUED) return "消息已经重新进入主交换机，请在通知中心确认消费结果。";
        if (message.getStatus() == DeadLetterStatus.DISCARDED) return "消息已由管理员作废，不会再次投递。";
        if (message.getStatus() == DeadLetterStatus.REQUEUEING) return "消息正在进行 Broker Confirm；超过一分钟可再次发起受控重投。";
        return switch (message.getDeadLetterReason().toLowerCase(Locale.ROOT)) {
            case "rejected" -> "消息在消费者重试耗尽后被拒绝。请检查负载和业务约束，修复后再重新入队。";
            case "expired" -> "消息因 TTL 到期进入死信。请检查队列时效策略后决定重投或作废。";
            case "maxlen", "maxlen-bytes" -> "队列达到容量限制。请先处理消费积压和容量配置。";
            default -> "请检查 Broker 头信息和原始负载，确认故障消除后再执行受控重投。";
        };
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Dead-letter payload is not valid JSON", ex);
        }
    }

    private String headersJson(Message message) {
        Map<String, Object> safe = new LinkedHashMap<>();
        message.getMessageProperties().getHeaders().forEach((key, value) -> safe.put(key, safeHeader(value)));
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JacksonException ex) {
            return "{\"serializationError\":\"" + ex.getClass().getSimpleName() + "\"}";
        }
    }

    private Object safeHeader(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), safeHeader(item)));
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            collection.forEach(item -> result.add(safeHeader(item)));
            return result;
        }
        return String.valueOf(value);
    }

    private long deathCount(Message message) {
        Object deaths = message.getMessageProperties().getHeaders().get("x-death");
        if (deaths instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object count = first.get("count");
            if (count instanceof Number number) return Math.max(1, number.longValue());
            try { return Math.max(1, Long.parseLong(String.valueOf(count))); }
            catch (NumberFormatException ignored) { return 1; }
        }
        return 1;
    }

    private String originalRoutingKey(Message message) {
        Object deaths = message.getMessageProperties().getHeaders().get("x-death");
        if (deaths instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object routingKeys = first.get("routing-keys");
            if (routingKeys instanceof List<?> keys && !keys.isEmpty()) return String.valueOf(keys.get(0));
        }
        return MessagingTopology.NOTIFICATION_ROUTING_KEY;
    }

    private Long longHeader(Message message, String name) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String header(Message message, String name, String fallback) {
        String value = value(message.getMessageProperties().getHeaders().get(name));
        return value == null ? fallback : value;
    }

    private String textField(JsonNode json, String name) {
        JsonNode value = json == null ? null : json.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private Long longField(JsonNode json, String name) {
        JsonNode value = json == null ? null : json.get(name);
        if (value == null || value.isNull() || !value.canConvertToLong()) return null;
        return value.asLong();
    }

    private String value(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String firstNonBlank(String first, String second) { return first == null || first.isBlank() ? second : first; }
    private Long firstNonNull(Long first, Long second) { return first == null ? second : first; }
    private String safeMessage(String message) { return message == null || message.isBlank() ? "无错误详情" : message; }
    private String truncate(String value, int maxLength) { return value.length() <= maxLength ? value : value.substring(0, maxLength); }

    private void appendCsvRow(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            csv.append(csvValue(values[index]));
        }
        csv.append('\n');
    }

    private String csvValue(Object value) {
        if (value == null) return "\"\"";
        String text = String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private record RequeueAttempt(Long id, String messageId, Long tenantId, String notificationType,
                                  String routingKey, String payloadJson, int requeueCount) { }

    public record DeadLetterSummary(long quarantined, long requeueing, long requeued, long discarded,
                                    LocalDateTime oldestQuarantinedAt) { }

    public record DeadLetterView(Long id, Long outboxEventId, String messageId, String notificationType,
                                 String title, Long recipientId, String dedupeKey, String sourceQueue,
                                 String originalRoutingKey, String deadLetterReason, Long deathCount,
                                 DeadLetterStatus status, Integer requeueCount, LocalDateTime quarantinedAt,
                                 LocalDateTime lastRequeuedAt, LocalDateTime resolvedAt,
                                 LocalDateTime updatedAt) { }

    public record DeadLetterDetail(DeadLetterView message, String payloadJson, String headersJson,
                                   String recommendation, String resolutionNote, String lastError) { }
}
