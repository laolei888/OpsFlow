package com.opsflow.alert;

public enum AlertSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    public boolean atLeast(AlertSeverity threshold) {
        return threshold != null && ordinal() <= threshold.ordinal();
    }
}
