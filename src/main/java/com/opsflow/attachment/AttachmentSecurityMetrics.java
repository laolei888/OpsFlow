package com.opsflow.attachment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class AttachmentSecurityMetrics {
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong infected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Counter cleanScans;
    private final Counter blockedScans;
    private final Counter failedScans;
    private final Counter manualReleases;

    public AttachmentSecurityMetrics(MeterRegistry registry) {
        Gauge.builder("opsflow.attachment.scan.pending", pending, AtomicLong::get)
                .description("Attachments waiting for or currently undergoing security scanning")
                .register(registry);
        Gauge.builder("opsflow.attachment.scan.infected", infected, AtomicLong::get)
                .description("Attachments retained in quarantine after threat detection")
                .register(registry);
        Gauge.builder("opsflow.attachment.scan.failed", failed, AtomicLong::get)
                .description("Attachments requiring manual action after scanning failed")
                .register(registry);
        cleanScans = Counter.builder("opsflow.attachment.scan.clean")
                .description("Attachments released automatically after a clean scan")
                .register(registry);
        blockedScans = Counter.builder("opsflow.attachment.scan.blocked")
                .description("Attachments blocked after threat detection")
                .register(registry);
        failedScans = Counter.builder("opsflow.attachment.scan.errors")
                .description("Terminal attachment scan failures")
                .register(registry);
        manualReleases = Counter.builder("opsflow.attachment.scan.manual_releases")
                .description("Quarantined attachments released manually by an administrator")
                .register(registry);
    }

    public void update(long pending, long infected, long failed) {
        this.pending.set(pending);
        this.infected.set(infected);
        this.failed.set(failed);
    }

    public void clean() { cleanScans.increment(); }
    public void blocked() { blockedScans.increment(); }
    public void failed() { failedScans.increment(); }
    public void released() { manualReleases.increment(); }
}
