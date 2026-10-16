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
    private final AtomicLong deadLetterQuarantined = new AtomicLong();
    private final AtomicLong deadLetterRequeueing = new AtomicLong();
    private final Counter publishAcknowledged;
    private final Counter consumeSucceeded;
    private final Counter consumeDeduplicated;
    private final Counter consumeFailed;
    private final Counter quarantineReceived;
    private final Counter quarantineRequeued;
    private final Counter quarantineDiscarded;

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
        Gauge.builder("opsflow.messaging.dead_letter.quarantined", deadLetterQuarantined, AtomicLong::get)
                .description("Consumer dead letters held in the governance quarantine")
                .register(registry);
        Gauge.builder("opsflow.messaging.dead_letter.requeueing", deadLetterRequeueing, AtomicLong::get)
                .description("Consumer dead letters currently being requeued")
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
        quarantineReceived = Counter.builder("opsflow.messaging.dead_letter.received")
                .description("Messages archived from the RabbitMQ dead-letter queue")
                .register(registry);
        quarantineRequeued = Counter.builder("opsflow.messaging.dead_letter.requeued")
                .description("Quarantined messages manually requeued")
                .register(registry);
        quarantineDiscarded = Counter.builder("opsflow.messaging.dead_letter.discarded")
                .description("Quarantined messages manually discarded")
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

    public void updateDeadLetterBacklog(long quarantined, long requeueing) {
        deadLetterQuarantined.set(quarantined);
        deadLetterRequeueing.set(requeueing);
    }

    public void deadLetterQuarantined() { quarantineReceived.increment(); }
    public void deadLetterRequeued() { quarantineRequeued.increment(); }
    public void deadLetterDiscarded() { quarantineDiscarded.increment(); }
}
