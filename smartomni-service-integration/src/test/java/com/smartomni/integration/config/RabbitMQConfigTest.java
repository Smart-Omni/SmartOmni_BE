package com.smartomni.integration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.*;

class RabbitMQConfigTest {

    private final RabbitMQConfig rabbitMQConfig = new RabbitMQConfig();

    @Test
    @DisplayName("RabbitMQConfig: declares orderExchange correctly")
    void testOrderExchange() {
        DirectExchange exchange = rabbitMQConfig.orderExchange();
        assertNotNull(exchange);
        assertEquals(RabbitMQConfig.ORDER_EXCHANGE, exchange.getName());
        assertTrue(exchange.isDurable());
        assertFalse(exchange.isAutoDelete());
    }

    @Test
    @DisplayName("RabbitMQConfig: declares return queue with DLQ arguments")
    void testReturnQueue() {
        Queue queue = rabbitMQConfig.returnQueue();
        assertNotNull(queue);
        assertEquals(RabbitMQConfig.RETURN_QUEUE, queue.getName());
        assertTrue(queue.isDurable());
        assertEquals("", queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitMQConfig.RETURN_DLQ, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    @DisplayName("RabbitMQConfig: declares return DLQ correctly")
    void testReturnDeadLetterQueue() {
        Queue dlq = rabbitMQConfig.returnDeadLetterQueue();
        assertNotNull(dlq);
        assertEquals(RabbitMQConfig.RETURN_DLQ, dlq.getName());
        assertTrue(dlq.isDurable());
    }

    @Test
    @DisplayName("RabbitMQConfig: binds return queue to order exchange with return.incoming")
    void testReturnBinding() {
        DirectExchange exchange = rabbitMQConfig.orderExchange();
        Queue queue = rabbitMQConfig.returnQueue();
        Binding binding = rabbitMQConfig.returnBinding(queue, exchange);

        assertNotNull(binding);
        assertEquals(RabbitMQConfig.RETURN_QUEUE, binding.getDestination());
        assertEquals(RabbitMQConfig.ORDER_EXCHANGE, binding.getExchange());
        assertEquals(RabbitMQConfig.RETURN_ROUTING_KEY, binding.getRoutingKey());
    }
}
