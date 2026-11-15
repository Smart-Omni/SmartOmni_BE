package com.smartomni.integration.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Khai bao Exchange/Queue/Binding cho luong dong bo don hang va hoan hang qua Webhook (UC-32, UC-33).
 */
@Configuration
public class RabbitMQConfig {

    public static final String ORDER_EXCHANGE = "smartomni.order.exchange";
    public static final String RETURN_QUEUE = "smartomni.return.incoming.queue";
    public static final String RETURN_ROUTING_KEY = "return.incoming";
    public static final String RETURN_DLQ = "smartomni.return.incoming.dlq";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue returnQueue() {
        return QueueBuilder.durable(RETURN_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", RETURN_DLQ)
                .build();
    }

    @Bean
    public Queue returnDeadLetterQueue() {
        return QueueBuilder.durable(RETURN_DLQ).build();
    }

    @Bean
    public Binding returnBinding(Queue returnQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(returnQueue).to(orderExchange).with(RETURN_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
