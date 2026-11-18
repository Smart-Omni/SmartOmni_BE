package com.smartomni.integration.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.service.tiktok.TiktokInventoryWebhookService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TiktokInventoryMessageConsumerTest {
    @Mock TiktokInventoryWebhookService inventory;

    @Test
    void appliesVerifiedQueueMessageWithinTenantScope() {
        var consumer = new TiktokInventoryMessageConsumer(new ObjectMapper(), inventory);
        consumer.receive(Map.of("tenantId", 42L, "connectionId", 8L, "payload",
                "{\"type\":71,\"shop_id\":\"shop-1\",\"data\":{\"sku_id\":\"SKU-1\"}}"));

        ArgumentCaptor<TiktokWebhookPayload> payload = ArgumentCaptor.forClass(TiktokWebhookPayload.class);
        verify(inventory).apply(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(8L), payload.capture());
        assertEquals("SKU-1", payload.getValue().getData().path("sku_id").asText());
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void malformedMessageIsRejectedForDeadLetterQueue() {
        var consumer = new TiktokInventoryMessageConsumer(new ObjectMapper(), inventory);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.receive(Map.of("payload", "{}")));
    }
}
