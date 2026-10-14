package com.opsflow.webhook;

public enum WebhookDeliveryStatus {
    PENDING,
    RETRYING,
    DELIVERED,
    DEAD
}
