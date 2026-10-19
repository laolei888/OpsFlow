package com.opsflow.notification;

public class NotificationDeliveryException extends RuntimeException {
    private final Integer responseStatus;
    private final boolean retryable;

    public NotificationDeliveryException(String message, Integer responseStatus, boolean retryable) {
        super(message);
        this.responseStatus = responseStatus;
        this.retryable = retryable;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
