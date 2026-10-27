package com.opsflow.workbench;

import java.time.LocalDateTime;
import java.util.List;

public record OperationsWorkbenchView(Summary summary, List<WorkItem> items, LocalDateTime generatedAt) {
    public record Summary(long total, long critical, long high, long approvals,
                          long firingAlerts, long activeIncidents,
                          long reliabilityBreaches, long unpublishedNotices,
                          long postmortemReviews, long knowledgeReviews,
                          long overdueKnowledgeReviews) { }

    public record WorkItem(Long id, String type, String typeLabel, String referenceNo,
                           String title, String description, String priority, String status,
                           String targetView, String actionLabel, boolean requiresAdmin,
                           LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
