package com.smartomni.order.mq;

import com.smartomni.order.config.RabbitMQConfig;
import com.smartomni.order.dto.WebhookOrderPayload;
import com.smartomni.order.entity.Order;
import com.smartomni.order.service.OrderService;
import com.smartomni.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * UC-33: Xu ly bat dong bo don hang (Async Order Processing).
 * Consumer lang nghe RabbitMQ, doc message do smartomni-service-integration
 * day vao sau khi nhan Webhook tu Shopee/TikTok Shop (UC-32), roi goi
 * OrderService de tao don hang + tru ton kho.
 *
 * FR-061: Background Worker xu ly bat dong bo.
 * FR-062: Neu xu ly loi, KHONG ack -> Durable Queue tu dong retry (cau hinh
 * requeue/backoff chi tiet o application.yml hoac RabbitMQConfig).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMessageConsumer {

    private final OrderService orderService;

    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    public void handleIncomingOrder(WebhookOrderPayload payload) {
        log.info("Nhan duoc order message: tenant={}, platform={}, orderId={}",
                payload.getTenantId(), payload.getPlatform(), payload.getPlatformOrderId());
        if (payload.getTenantId() == null || payload.getTenantId() <= 0) {
            throw new IllegalArgumentException("Incoming order must have a valid tenant ID");
        }
        Order.OrderSource source = "POLLING".equalsIgnoreCase(payload.getSource())
                ? Order.OrderSource.POLLING
                : Order.OrderSource.WEBHOOK;
        try (var scope = TenantContext.openScope(payload.getTenantId(), "manager", null)) {
            orderService.processIncomingOrder(payload, source);
        } catch (Exception ex) {
            log.error("Xu ly order message that bai, se duoc retry/DLQ: {}", ex.getMessage());
            throw ex; // nem lai de RabbitMQ ap dung co che retry/dead-letter (FR-062)
        }
    }
}
