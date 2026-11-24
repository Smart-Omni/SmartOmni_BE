package com.smartomni.order.mq;

import com.smartomni.order.dto.WebhookOrderPayload;
import com.smartomni.order.entity.Order;
import com.smartomni.order.service.OrderService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderMessageConsumerTest {

    private final OrderService orderService = mock(OrderService.class);
    private final OrderMessageConsumer consumer = new OrderMessageConsumer(orderService);

    private static WebhookOrderPayload payload(String source) {
        WebhookOrderPayload payload = new WebhookOrderPayload();
        payload.setTenantId(3L);
        payload.setPlatform("TIKTOK_SHOP");
        payload.setPlatformOrderId("O-1");
        payload.setSource(source);
        return payload;
    }

    @Test
    void pollingSourceIsKept() {
        consumer.handleIncomingOrder(payload("POLLING"));
        verify(orderService).processIncomingOrder(any(), eq(Order.OrderSource.POLLING));
    }

    @Test
    void missingSourceIsWebhook() {
        consumer.handleIncomingOrder(payload(null));
        verify(orderService).processIncomingOrder(any(), eq(Order.OrderSource.WEBHOOK));
    }
}
