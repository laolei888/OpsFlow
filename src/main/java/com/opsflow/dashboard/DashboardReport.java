package com.opsflow.dashboard;

import java.time.LocalDate;
import java.util.List;

public record DashboardReport(
        LocalDate from,
        LocalDate to,
        long createdTickets,
        long resolvedTickets,
        long activeTickets,
        long overdueTickets,
        double averageResolutionHours,
        int slaComplianceRate,
        long slaResolvedSample,
        List<AssigneeMetric> assignees,
        List<CategoryMetric> categories) {

    public record AssigneeMetric(
            Long assigneeId,
            long createdTickets,
            long resolvedTickets,
            long activeTickets,
            double averageResolutionHours,
            int slaComplianceRate) { }

    public record CategoryMetric(
            String category,
            long createdTickets,
            long resolvedTickets,
            long activeTickets) { }
}