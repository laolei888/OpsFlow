package com.opsflow.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class AsyncMessagingMetrics {
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final Counter publishAcknowledged;
    private final Counter consumeSucceeded;
    private final Counter consumeDeduplicated;
    private final Counter consumeFailed;

    public AsyncMessagingMetrics(MeterRegistry registry) {
        Gauge.builder("opsflow.messaging.outbox.pending", pending, AtomicLong::get)
                .description("Async messages waiting to be published")
                .register(registry);
        Gauge.builder("opsflow.messaging.outbox.failed", failed, AtomicLong::get)
                .description("Async messages waiting for a publish retry")
                .register(registry);
        Gauge.builder("opsflow.messaging.outbox.dead", dead, AtomicLong::get)
                .description("Async messages exhausted after publish retries")
                .register(registry);
        publishAcknowledged = Counter.builder("opsflow.messaging.publish.acknowledged")
                .description("Messages acknowledged by RabbitMQ")
                .register(registry);
        consumeSucceeded = Counter.builder("opsflow.messaging.consume.succeeded")
                .description("Messages consumed successfully")
                .register(registry);
        consumeDeduplicated = Counter.builder("opsflow.messaging.consume.deduplicated")
                .description("Duplicate messages ignored by the consumer")
                .register(registry);
        consumeFailed = Counter.builder("opsflow.messaging.consume.failed")
                .description("Message consumer failures before retry or dead-lettering")
                .register(registry);
    }

    public void updateBacklog(long pending, long failed, long dead) {
        this.pending.set(pending);
        this.failed.set(failed);
        this.dead.set(dead);
    }

    public void published() {
        publishAcknowledged.increment();
    }

    public void consumed(boolean created) {
        (created ? consumeSucceeded : consumeDeduplicated).increment();
    }

    public void consumeFailed() {
        consumeFailed.increment();
    }
}
