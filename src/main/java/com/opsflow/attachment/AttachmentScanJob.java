package com.opsflow.attachment;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AttachmentScanJob {
    private static final Logger log = LoggerFactory.getLogger(AttachmentScanJob.class);
    private final AttachmentScanService scanService;

    @Scheduled(fixedDelayString = "${opsflow.attachment-security.scan-interval-ms:10000}",
            initialDelayString = "${opsflow.attachment-security.initial-delay-ms:12000}")
    public void scan() {
        try {
            int processed = scanService.scanPending(20);
            scanService.refreshMetrics();
            if (processed > 0) log.info("Attachment security worker processed {} quarantined file(s)", processed);
        } catch (Exception ex) {
            log.error("Attachment security worker failed", ex);
        }
    }
}
