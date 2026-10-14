package com.opsflow.webhook;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebhookDeliveryJob {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryJob.class);

    private final WebhookDeliveryService deliveryService;
    private final WebhookOutboxService outboxService;

    @Scheduled(
            fixedDelayString = "${opsflow.webhook.delivery-interval-ms:15000}",
            initialDelayString = "${opsflow.webhook.initial-delay-ms:20000}")
    public void scheduledDelivery() {
        try {
            int materialized = outboxService.dispatchPending(50);
            int processed = deliveryService.deliverPending(20);
            if (materialized > 0 || processed > 0) {
                log.info("Webhook worker materialized {} outbox event(s) and processed {} delivery record(s)",
                        materialized, processed);
            }
        } catch (Exception ex) {
            log.error("Webhook delivery worker failed", ex);
        }
    }

    @Scheduled(cron = "${opsflow.webhook.outbox-cleanup-cron:0 30 3 * * *}")
    public void cleanupOutbox() {
        try {
            int deleted = outboxService.purgePublished(30);
            if (deleted > 0) log.info("Webhook outbox retention cleanup removed {} published event(s)", deleted);
        } catch (Exception ex) {
            log.error("Webhook outbox retention cleanup failed", ex);
        }
    }
}
