package com.opsflow.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opsflow.notification.email.enabled", havingValue = "true")
public class EmailNotificationChannelAdapter implements NotificationChannelAdapter {
    private final JavaMailSender mailSender;
    private final String from;

    public EmailNotificationChannelAdapter(JavaMailSender mailSender,
                                           @Value("${opsflow.notification.email.from:}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context) {
        if (from == null || from.isBlank()) {
            throw new NotificationDeliveryException("邮件发件地址未配置", null, false);
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from.trim());
        message.setTo(context.delivery().getDestination());
        String title = context.delivery().getRenderedTitle() == null
                ? context.notification().getTitle() : context.delivery().getRenderedTitle();
        String content = context.delivery().getRenderedContent() == null
                ? context.notification().getContent() : context.delivery().getRenderedContent();
        message.setSubject("[OpsFlow] " + title);
        message.setText(content);
        mailSender.send(message);
        return new DeliveryResult(null);
    }
}
