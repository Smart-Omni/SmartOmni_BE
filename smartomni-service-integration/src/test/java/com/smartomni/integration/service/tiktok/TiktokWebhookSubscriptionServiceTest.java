package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.TiktokProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiktokWebhookSubscriptionServiceTest {
    private static final String HOOK = "https://example.test/api/v1/integrations/tiktok/webhooks";
    private static final Set<String> ALL_TOPICS = Set.of("ORDER_STATUS_CHANGE", "PACKAGE_UPDATE", "RETURN_STATUS_CHANGE",
            "CANCELLATION_STATUS_CHANGE", "SELLER_DEAUTHORIZATION", "INVENTORY_CHANGED_BY_SHOP");

    private final ObjectMapper mapper = new ObjectMapper();
    private final TiktokShopApiExecutor api = mock(TiktokShopApiExecutor.class);
    private final TiktokProperties properties = new TiktokProperties();
    private final TiktokWebhookSubscriptionService service =
            new TiktokWebhookSubscriptionService(api, properties, mapper);

    @Test
    void subscribesAllSixTopicsWithOnePutEach() throws Exception {
        properties.setWebhookUrl(HOOK);
        when(api.call(eq(7L), eq("GET"), anyString(), eq(Map.of()), isNull()))
                .thenReturn(mapper.readTree("{\"webhooks\":[]}"));

        assertTrue(service.ensureSubscribed(7L));

        assertEquals(ALL_TOPICS, putTopics(6));
    }

    @Test
    void reRunDoesNotSubscribeTopicsAlreadyRegisteredForTheAddress() throws Exception {
        properties.setWebhookUrl(HOOK);
        when(api.call(eq(7L), eq("GET"), anyString(), eq(Map.of()), isNull()))
                .thenReturn(mapper.readTree("{\"webhooks\":["
                        + "{\"event_type\":\"ORDER_STATUS_CHANGE\",\"address\":\"" + HOOK + "\"},"
                        + "{\"event_type\":\"INVENTORY_CHANGED_BY_SHOP\",\"address\":\"" + HOOK + "\"},"
                        + "{\"event_type\":\"PACKAGE_UPDATE\",\"address\":\"https://old.test/hook\"}]}"));

        assertTrue(service.ensureSubscribed(7L));

        // PACKAGE_UPDATE points at another address, so it is re-registered.
        assertEquals(Set.of("PACKAGE_UPDATE", "RETURN_STATUS_CHANGE", "CANCELLATION_STATUS_CHANGE", "SELLER_DEAUTHORIZATION"),
                putTopics(4));
    }

    @Test
    void leavesFullySubscribedConnectionAlone() throws Exception {
        properties.setWebhookUrl(HOOK);
        String hooks = ALL_TOPICS.stream()
                .map(t -> "{\"event_type\":\"" + t + "\",\"address\":\"" + HOOK + "\"}")
                .collect(Collectors.joining(","));
        when(api.call(eq(7L), eq("GET"), anyString(), eq(Map.of()), isNull()))
                .thenReturn(mapper.readTree("{\"webhooks\":[" + hooks + "]}"));

        assertTrue(service.ensureSubscribed(7L));

        verify(api, never()).call(eq(7L), eq("PUT"), anyString(), anyMap(), anyString());
    }

    @Test
    void fallsBackToTheInventoryWebhookUrl() throws Exception {
        properties.setInventoryWebhookUrl(HOOK);
        when(api.call(eq(7L), eq("GET"), anyString(), eq(Map.of()), isNull()))
                .thenReturn(mapper.readTree("{\"webhooks\":[]}"));

        assertTrue(service.ensureSubscribed(7L));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(api, times(6)).call(eq(7L), eq("PUT"), eq("/event/202309/webhooks"), eq(Map.of()), body.capture());
        assertEquals(HOOK, mapper.readTree(body.getValue()).path("address").asText());
    }

    @Test
    void disabledWithoutCallbackAddress() {
        assertFalse(service.ensureSubscribed(7L));
        verifyNoInteractions(api);
    }

    private Set<String> putTopics(int expectedCalls) throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(api, times(expectedCalls)).call(eq(7L), eq("PUT"), eq("/event/202309/webhooks"), eq(Map.of()), body.capture());
        List<String> topics = new java.util.ArrayList<>();
        for (String b : body.getAllValues()) {
            assertEquals(HOOK, mapper.readTree(b).path("address").asText());
            topics.add(mapper.readTree(b).path("event_type").asText());
        }
        assertEquals(expectedCalls, Set.copyOf(topics).size(), "no topic is registered twice");
        return Set.copyOf(topics);
    }
}
