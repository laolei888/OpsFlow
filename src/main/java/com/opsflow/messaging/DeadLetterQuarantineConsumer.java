package com.opsflow.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeadLetterQuarantineConsumer {
    private final DeadLetterMessageService deadLetterMessageService;

    public DeadLetterQuarantineConsumer(DeadLetterMessageService deadLetterMessageService) {
        this.deadLetterMessageService = deadLetterMessageService;
    }

    @Transactional
    @RabbitListener(queues = MessagingTopology.NOTIFICATION_DEAD_QUEUE,
            containerFactory = "deadLetterListenerContainerFactory",
            concurrency = "${opsflow.messaging.dead-letter-concurrency:1}")
    public void quarantine(Message message) {
        deadLetterMessageService.quarantine(message);
    }
}
