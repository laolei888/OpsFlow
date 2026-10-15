package com.opsflow.messaging;

public enum AsyncMessageOutboxStatus {
    PENDING,
    PROCESSING,
    FAILED,
    DEAD,
    PUBLISHED
}
