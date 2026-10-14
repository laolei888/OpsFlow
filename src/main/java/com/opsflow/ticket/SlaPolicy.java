package com.opsflow.ticket;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Keeps the first version of OpsFlow's SLA policy in one place so that ticket
 * creation, reporting and future scheduled reminders use the same rule.
 */
public final class SlaPolicy {
    private SlaPolicy() { }

    public static Duration durationFor(TicketPriority priority) {
        return switch (priority == null ? TicketPriority.MEDIUM : priority) {
            case URGENT -> Duration.ofHours(4);
            case HIGH -> Duration.ofHours(8);
            case MEDIUM -> Duration.ofHours(24);
            case LOW -> Duration.ofHours(72);
        };
    }

    public static LocalDateTime dueAt(TicketPriority priority, LocalDateTime createdAt) {
        return createdAt.plus(durationFor(priority));
    }
}