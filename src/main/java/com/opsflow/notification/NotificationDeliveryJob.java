package com.opsflow.notification;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryJob {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryJob.class);
    private final NotificationDeliveryService deliveryService;

    @Scheduled(fixedDelayString = "${opsflow.notification.delivery-interval-ms:10000}",
            initialDelayString = "${opsflow.notification.initial-delay-ms:15000}")
    public void deliver() {
        try {
            int processed = deliveryService.deliverPending(30);
            deliveryService.refreshMetrics();
            if (processed > 0) log.info("Notification channel worker processed {} delivery record(s)", processed);
        } catch (Exception ex) {
            log.error("Notification channel worker failed", ex);
        }
    }
}
