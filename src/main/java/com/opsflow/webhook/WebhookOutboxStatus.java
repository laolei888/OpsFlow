package com.opsflow.webhook;

public enum WebhookOutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED,
    DEAD
}
