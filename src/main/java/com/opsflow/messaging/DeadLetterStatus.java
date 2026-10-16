package com.opsflow.messaging;

public enum DeadLetterStatus {
    QUARANTINED,
    REQUEUEING,
    REQUEUED,
    DISCARDED
}
