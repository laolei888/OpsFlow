package com.opsflow.notification;

public interface NotificationChannelAdapter {
    NotificationChannel channel();

    DeliveryResult deliver(DeliveryContext context) throws Exception;

    record DeliveryContext(NotificationDelivery delivery, Notification notification) { }

    record DeliveryResult(Integer responseStatus) { }
}
