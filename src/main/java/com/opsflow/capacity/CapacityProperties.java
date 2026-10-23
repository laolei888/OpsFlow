package com.opsflow.capacity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "opsflow.capacity")
public class CapacityProperties {
    private String appVersion = "local";
    private long captureIntervalMs = 3_600_000;
    private int retentionDays = 180;
    private Thresholds thresholds = new Thresholds();

    @Data
    public static class Thresholds {
        private long activeTickets = 5_000;
        private long overdueTickets = 500;
        private long activeIncidents = 20;
        private long firingAlerts = 100;
        private long asyncBacklog = 1_000;
        private long attachmentBacklog = 500;
        private long attachmentBytes = 5_368_709_120L;
        private long databaseBytes = 10_737_418_240L;
    }
}
