package com.opsflow.attachment;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AttachmentStorageGovernanceJob {
    private static final Logger log = LoggerFactory.getLogger(AttachmentStorageGovernanceJob.class);

    private final AttachmentStoragePolicyMapper policyMapper;
    private final AttachmentStorageGovernanceService governanceService;
    private final AttachmentStorageProperties properties;

    @Scheduled(
            fixedDelayString = "${opsflow.storage.governance.reconcile-interval-ms:21600000}",
            initialDelayString = "${opsflow.storage.governance.reconcile-initial-delay-ms:120000}")
    public void reconcileKnownTenants() {
        if (!properties.getGovernance().isEnabled()) return;
        for (Long tenantId : policyMapper.selectKnownTenantIds()) {
            try {
                var result = governanceService.reconcile(tenantId, null);
                if (result.detected() > 0 || result.resolved() > 0 || result.truncated()) {
                    log.info("Attachment storage governance tenant={} detected={} missing={} resolved={} "
                                    + "truncated={}", tenantId, result.detected(), result.missing(),
                            result.resolved(), result.truncated());
                }
            } catch (RuntimeException ex) {
                log.warn("Attachment storage governance failed for tenant {}", tenantId, ex);
            }
        }
    }
}
