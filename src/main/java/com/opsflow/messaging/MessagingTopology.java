package com.opsflow.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class MessagingTopology {
    public static final String EXCHANGE = "opsflow.events.v1";
    public static final String DEAD_LETTER_EXCHANGE = "opsflow.events.dlx.v1";
    public static final String NOTIFICATION_QUEUE = "opsflow.notifications.v1";
    public static final String NOTIFICATION_DEAD_QUEUE = "opsflow.notifications.dead.v1";
    public static final String NOTIFICATION_ROUTING_KEY = "notification.requested.v1";
    public static final String NOTIFICATION_DEAD_ROUTING_KEY = "notification.dead.v1";

    @Bean
    DirectExchange opsFlowEventExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange opsFlowDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(NOTIFICATION_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue notificationDeadQueue() {
        return QueueBuilder.durable(NOTIFICATION_DEAD_QUEUE).build();
    }

    @Bean
    Binding notificationBinding(@Qualifier("notificationQueue") Queue notificationQueue,
                                @Qualifier("opsFlowEventExchange") DirectExchange opsFlowEventExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(opsFlowEventExchange)
                .with(NOTIFICATION_ROUTING_KEY);
    }

    @Bean
    Binding notificationDeadBinding(@Qualifier("notificationDeadQueue") Queue notificationDeadQueue,
                                    @Qualifier("opsFlowDeadLetterExchange")
                                    DirectExchange opsFlowDeadLetterExchange) {
        return BindingBuilder.bind(notificationDeadQueue)
                .to(opsFlowDeadLetterExchange)
                .with(NOTIFICATION_DEAD_ROUTING_KEY);
    }
}
