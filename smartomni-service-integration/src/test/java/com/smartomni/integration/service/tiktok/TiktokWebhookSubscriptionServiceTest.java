package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.TiktokProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiktokInventorySubscriptionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TiktokShopApiExecutor api = mock(TiktokShopApiExecutor.class);
    private final TiktokProperties properties = new TiktokProperties();
    private final TiktokInventorySubscriptionService service =
            new TiktokInventorySubscriptionService(api, properties, mapper);

    @Test
    void subscribesWhenTopicIsMissing() throws Exception {
        properties.setInventoryWebhookUrl("https://example.test/api/v1/integrations/tiktok/webhooks");
        when(api.call(eq(7L), eq("GET"), anyString(), eq(Map.of()), isNull()))
                .thenReturn(mapper.readTree("{\"webhooks\":[]}"));

        assertTrue(service.ensureSubscribed(7L));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(api).call(eq(7L), eq("PUT"), eq("/event/202309/webhooks"), eq(Map.of()), body.capture());
        assertEquals("INVENTORY_CHANGED_BY_SHOP", mapper.readTree(body.getValue()).path("event_type").asText());
        assertEquals(properties.getInventoryWebhookUrl(), mapper.readTree(body.getValue()).path("address").asText());
    }

    @Test
    void leavesMatchingSubscriptionAlone() throws Exception {
        properties.setInventoryWebhookUrl("https://example.test/hook");
        when(api.call(eq(7L), eq("GET"), anyString(), eq(Map.of()), isNull()))
                .thenReturn(mapper.readTree("{\"webhooks\":[{\"event_type\":\"INVENTORY_CHANGED_BY_SHOP\",\"address\":\"https://example.test/hook\"}]}"));
        assertTrue(service.ensureSubscribed(7L));
        verify(api, never()).call(eq(7L), eq("PUT"), anyString(), anyMap(), anyString());
    }

    @Test
    void disabledWithoutCallbackAddress() {
        assertFalse(service.ensureSubscribed(7L));
        verifyNoInteractions(api);
    }
}
