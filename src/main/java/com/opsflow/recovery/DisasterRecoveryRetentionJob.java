package com.opsflow.recovery;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DisasterRecoveryRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(DisasterRecoveryRetentionJob.class);

    private final DisasterRecoveryPolicyMapper policyMapper;
    private final DisasterRecoveryService service;

    @Scheduled(cron = "0 15 3 * * *")
    public void expireCatalogEntries() {
        for (Long tenantId : policyMapper.selectKnownTenantIds()) {
            try {
                int expired = service.expireSnapshots(tenantId);
                if (expired > 0) log.info("Marked {} backup catalog entries expired for tenant {}", expired, tenantId);
            } catch (RuntimeException ex) {
                log.error("Failed to apply backup retention for tenant {}", tenantId, ex);
            }
        }
    }
}
