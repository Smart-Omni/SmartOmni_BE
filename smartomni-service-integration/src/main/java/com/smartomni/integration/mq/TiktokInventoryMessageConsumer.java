package com.smartomni.integration.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.service.tiktok.TiktokInventoryWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class TiktokInventoryMessageConsumer {
    private final ObjectMapper mapper;
    private final TiktokInventoryWebhookService inventory;

    @RabbitListener(queues = RabbitMQConfig.INVENTORY_QUEUE)
    public void receive(Map<String, Object> message) {
        try {
            long tenantId = ((Number) message.get("tenantId")).longValue();
            long connectionId = ((Number) message.get("connectionId")).longValue();
            String rawPayload = (String) message.get("payload");
            TiktokWebhookPayload payload = mapper.readValue(rawPayload, TiktokWebhookPayload.class);
            try (var scope = TenantContext.openScope(tenantId, "tenant_admin", null)) {
                inventory.apply(tenantId, connectionId, payload);
            }
        } catch (Exception ex) {
            throw new AmqpRejectAndDontRequeueException("TikTok inventory webhook could not be applied", ex);
        }
    }
}
