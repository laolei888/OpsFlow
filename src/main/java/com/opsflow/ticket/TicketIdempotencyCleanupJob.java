package com.opsflow.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class TicketIdempotencyCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(TicketIdempotencyCleanupJob.class);

    private final TicketIdempotencyKeyMapper idempotencyKeyMapper;

    @Value("${opsflow.idempotency.retention-hours:24}")
    private int retentionHours;

    @Scheduled(
            fixedDelayString = "${opsflow.idempotency.cleanup-interval-ms:3600000}",
            initialDelayString = "${opsflow.idempotency.initial-delay-ms:60000}")
    @Transactional
    public void scheduledCleanup() {
        try {
            int deleted = cleanupExpired(LocalDateTime.now().minusHours(Math.max(1, retentionHours)));
            if (deleted > 0) log.info("Idempotency cleanup removed {} expired key(s)", deleted);
        } catch (Exception ex) {
            log.error("Idempotency cleanup failed", ex);
        }
    }

    int cleanupExpired(LocalDateTime cutoff) {
        return idempotencyKeyMapper.delete(new LambdaQueryWrapper<TicketIdempotencyKey>()
                .lt(TicketIdempotencyKey::getCreatedAt, cutoff));
    }
}
