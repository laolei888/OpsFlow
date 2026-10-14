package com.opsflow.alert;

public enum AlertEventType {
    TRIGGERED,
    REPEATED,
    ACKNOWLEDGED,
    RESOLVED,
    REOPENED,
    SUPPRESSED,
    RULE_MATCHED,
    TICKET_CREATED,
    INCIDENT_CREATED
}
