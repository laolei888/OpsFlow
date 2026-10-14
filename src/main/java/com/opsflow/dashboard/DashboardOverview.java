package com.opsflow.dashboard;

import java.util.List;

public record DashboardOverview(
        long total,
        long active,
        long overdue,
        long dueSoon,
        long resolved,
        long urgent,
        long unassigned,
        long assigned,
        long createdToday,
        long resolvedToday,
        List<Metric> statuses,
        List<Metric> priorities) {

    public record Metric(String key, String label, long count) { }
}