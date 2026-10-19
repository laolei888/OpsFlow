package com.opsflow.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class NotificationDeliveryMetrics {
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final Map<NotificationChannel, Counter> succeeded = new EnumMap<>(NotificationChannel.class);
    private final Map<NotificationChannel, Counter> failed = new EnumMap<>(NotificationChannel.class);

    public NotificationDeliveryMetrics(MeterRegistry registry) {
        Gauge.builder("opsflow.notification.delivery.pending", pending, AtomicLong::get)
                .description("Notification channel deliveries waiting or retrying")
                .register(registry);
        Gauge.builder("opsflow.notification.delivery.dead", dead, AtomicLong::get)
                .description("Notification channel deliveries requiring manual intervention")
                .register(registry);
        for (NotificationChannel channel : NotificationChannel.values()) {
            succeeded.put(channel, Counter.builder("opsflow.notification.delivery.succeeded")
                    .tag("channel", channel.name().toLowerCase())
                    .description("Successful notification channel deliveries")
                    .register(registry));
            failed.put(channel, Counter.builder("opsflow.notification.delivery.failed")
                    .tag("channel", channel.name().toLowerCase())
                    .description("Failed notification channel delivery attempts")
                    .register(registry));
        }
    }

    public void updateBacklog(long pending, long dead) {
        this.pending.set(pending);
        this.dead.set(dead);
    }

    public void succeeded(NotificationChannel channel) {
        succeeded.get(channel).increment();
    }

    public void failed(NotificationChannel channel) {
        failed.get(channel).increment();
    }
}
