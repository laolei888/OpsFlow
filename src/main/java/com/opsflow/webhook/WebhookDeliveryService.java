package com.opsflow.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class WebhookDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long CLAIM_LEASE_SECONDS = 60L;

    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookEndpointMapper endpointMapper;
    private final TransactionTemplate transactionTemplate;
    private final HttpClient httpClient;

    public WebhookDeliveryService(WebhookDeliveryMapper deliveryMapper,
                                  WebhookEndpointMapper endpointMapper,
                                  PlatformTransactionManager transactionManager) {
        this.deliveryMapper = deliveryMapper;
        this.endpointMapper = endpointMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public int deliverPending(int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 100));
        List<WebhookDelivery> due = deliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                .in(WebhookDelivery::getStatus, List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRYING))
                .le(WebhookDelivery::getNextAttemptAt, LocalDateTime.now())
                .orderByAsc(WebhookDelivery::getNextAttemptAt)
                .orderByAsc(WebhookDelivery::getId)
                .last("LIMIT " + safeBatchSize));
        int processed = 0;
        for (WebhookDelivery delivery : due) {
            try {
                deliverOne(delivery.getId(), delivery.getTenantId());
                processed++;
            } catch (Exception ex) {
                log.warn("Webhook delivery {} failed unexpectedly", delivery.getId(), ex);
            }
        }
        return processed;
    }

    public void deliverOne(Long deliveryId, Long tenantId) {
        DeliveryAttempt attempt = transactionTemplate.execute(status -> claim(deliveryId, tenantId));
        if (attempt == null) return;

        HttpResponse<String> response = null;
        Exception failure = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(attempt.url()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "OpsFlow-Webhook/1.0")
                    .header("X-OpsFlow-Event", attempt.eventType())
                    .header("X-OpsFlow-Delivery", attempt.eventId())
                    .header("X-OpsFlow-Signature", "sha256=" + attempt.signature())
                    .POST(HttpRequest.BodyPublishers.ofString(attempt.payloadJson()))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            failure = ex;
        }

        HttpResponse<String> finalResponse = response;
        Exception finalFailure = failure;
        transactionTemplate.executeWithoutResult(status -> complete(attempt, finalResponse, finalFailure));
    }

    private DeliveryAttempt claim(Long deliveryId, Long tenantId) {
        WebhookDelivery delivery = deliveryMapper.selectForUpdate(deliveryId, tenantId);
        if (delivery == null || delivery.getStatus() == WebhookDeliveryStatus.DELIVERED
                || delivery.getStatus() == WebhookDeliveryStatus.DEAD) return null;
        if (delivery.getNextAttemptAt() != null && delivery.getNextAttemptAt().isAfter(LocalDateTime.now())) return null;
        WebhookEndpoint endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<WebhookEndpoint>()
                .eq(WebhookEndpoint::getId, delivery.getEndpointId())
                .eq(WebhookEndpoint::getTenantId, tenantId));
        if (endpoint == null || !Boolean.TRUE.equals(endpoint.getEnabled())) {
            delivery.setStatus(WebhookDeliveryStatus.DEAD);
            delivery.setLastError(endpoint == null ? "Webhook 集成已删除" : "Webhook 集成已停用");
            delivery.setUpdatedAt(LocalDateTime.now());
            deliveryMapper.updateById(delivery);
            return null;
        }
        int attemptCount = (delivery.getAttemptCount() == null ? 0 : delivery.getAttemptCount()) + 1;
        delivery.setAttemptCount(attemptCount);
        delivery.setStatus(WebhookDeliveryStatus.RETRYING);
        delivery.setResponseStatus(null);
        delivery.setLastError(null);
        LocalDateTime claimTime = LocalDateTime.now();
        delivery.setNextAttemptAt(claimTime.plusSeconds(CLAIM_LEASE_SECONDS));
        delivery.setUpdatedAt(claimTime);

        deliveryMapper.updateById(delivery);
        return new DeliveryAttempt(delivery.getId(), delivery.getTenantId(), endpoint.getUrl(),
                delivery.getEventId(), delivery.getEventType(), delivery.getPayloadJson(), delivery.getSignature(),
                attemptCount);
    }

    private void complete(DeliveryAttempt attempt, HttpResponse<String> response, Exception failure) {
        WebhookDelivery delivery = deliveryMapper.selectForUpdate(attempt.deliveryId(), attempt.tenantId());
        if (delivery == null || delivery.getStatus() == WebhookDeliveryStatus.DEAD
                || delivery.getStatus() == WebhookDeliveryStatus.DELIVERED) return;
        LocalDateTime now = LocalDateTime.now();
        int responseStatus = response == null ? 0 : response.statusCode();
        boolean success = response != null && responseStatus >= 200 && responseStatus < 300;
        if (success) {
            delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
            delivery.setResponseStatus(responseStatus);
            delivery.setLastError(null);
            delivery.setDeliveredAt(now);
            delivery.setNextAttemptAt(now);
        } else {
            String error = failure == null
                    ? "目标服务返回 HTTP " + responseStatus
                    : failure.getClass().getSimpleName() + ": " + safeMessage(failure.getMessage());
            boolean dead = attempt.attemptCount() >= MAX_ATTEMPTS;
            delivery.setStatus(dead ? WebhookDeliveryStatus.DEAD : WebhookDeliveryStatus.RETRYING);
            delivery.setResponseStatus(response == null ? null : responseStatus);
            delivery.setLastError(error);
            delivery.setNextAttemptAt(now.plusSeconds(backoffSeconds(attempt.attemptCount())));
        }
        delivery.setUpdatedAt(now);
        deliveryMapper.updateById(delivery);
    }

    private long backoffSeconds(int attemptCount) {
        long seconds = 30L * (1L << Math.min(6, Math.max(0, attemptCount - 1)));
        return Math.min(seconds, 3600L);
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) return "无响应信息";
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    private record DeliveryAttempt(Long deliveryId, Long tenantId, String url, String eventId,
                                   String eventType, String payloadJson, String signature,
                                   int attemptCount) { }
}
