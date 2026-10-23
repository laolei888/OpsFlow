package com.opsflow.capacity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CapacitySnapshotJob {
    private final CapacitySnapshotCollector collector;
    private final CapacityPlanningService capacityPlanningService;

    @Scheduled(fixedDelayString = "${opsflow.capacity.capture-interval-ms:3600000}",
            initialDelayString = "${opsflow.capacity.initial-delay-ms:30000}")
    public void captureActiveTenants() {
        for (Long tenantId : collector.activeTenantIds()) {
            try {
                capacityPlanningService.captureScheduled(tenantId);
            } catch (RuntimeException exception) {
                log.warn("Capacity snapshot failed for tenant {}: {}", tenantId, exception.getMessage());
            }
        }
        capacityPlanningService.purgeExpired();
    }
}
