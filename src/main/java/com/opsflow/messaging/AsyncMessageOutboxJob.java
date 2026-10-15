package com.opsflow.messaging;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AsyncMessageOutboxJob {
    private static final Logger log = LoggerFactory.getLogger(AsyncMessageOutboxJob.class);

    private final AsyncMessageOutboxService outboxService;

    @Scheduled(
            fixedDelayString = "${opsflow.messaging.outbox-interval-ms:5000}",
            initialDelayString = "${opsflow.messaging.initial-delay-ms:10000}")
    public void publishPending() {
        try {
            int published = outboxService.dispatchPending(100);
            if (published > 0) log.info("RabbitMQ publisher acknowledged {} asynchronous message(s)", published);
        } catch (Exception ex) {
            log.error("Asynchronous message outbox worker failed", ex);
        }
    }

    @Scheduled(cron = "${opsflow.messaging.outbox-cleanup-cron:0 45 3 * * *}")
    public void cleanupPublished() {
        try {
            int deleted = outboxService.purgePublished(30);
            if (deleted > 0) log.info("Asynchronous message cleanup removed {} published event(s)", deleted);
        } catch (Exception ex) {
            log.error("Asynchronous message cleanup failed", ex);
        }
    }
}
