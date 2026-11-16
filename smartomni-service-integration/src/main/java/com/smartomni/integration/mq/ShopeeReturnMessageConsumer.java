package com.smartomni.integration.mq;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.service.order.ShopeeReturnSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumer listening to RabbitMQ return.incoming queue (smartomni.return.incoming.queue).
 * Processes return events dispatched by ShopeeWebhookDispatcher (FR-061, UC-33, DS-09),
 * queries Shopee return details, and notifies Order Service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopeeReturnMessageConsumer {

    private final ShopeeReturnSyncService returnSyncService;

    @RabbitListener(queues = RabbitMQConfig.RETURN_QUEUE)
    public void handleIncomingReturn(Map<String, Object> message) {
        log.info("Received return message from RabbitMQ: {}", message);

        if (message == null || !message.containsKey("returnSn")) {
            log.warn("Invalid return message received without returnSn: {}", message);
            return;
        }

        Long tenantId = null;
        if (message.get("tenantId") instanceof Number num) {
            tenantId = num.longValue();
        } else if (message.get("tenantId") != null) {
            try {
                tenantId = Long.parseLong(String.valueOf(message.get("tenantId")));
            } catch (NumberFormatException ignored) {}
        }

        if (tenantId == null || tenantId <= 0) {
            log.error("Incoming return message missing valid tenantId: {}", message);
            throw new IllegalArgumentException("Incoming return must have a valid tenant ID");
        }

        String shopId = message.get("shopId") != null ? String.valueOf(message.get("shopId")) : null;
        String orderSn = message.get("platformOrderId") != null ? String.valueOf(message.get("platformOrderId")) : null;
        String returnSn = String.valueOf(message.get("returnSn"));

        try (var scope = TenantContext.openScope(tenantId, "staff", null)) {
            returnSyncService.syncReturn(tenantId, shopId, orderSn, returnSn);
            log.info("Successfully processed return message: tenantId={}, returnSn={}, orderSn={}",
                    tenantId, returnSn, orderSn);
        } catch (Exception ex) {
            log.error("Failed to process return message for returnSn={}, will retry/DLQ: {}", returnSn, ex.getMessage());
            throw ex;
        }
    }
}
