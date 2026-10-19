package com.opsflow.notification;

public enum NotificationDeliveryStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    DELIVERED,
    DEAD,
    SKIPPED
}
