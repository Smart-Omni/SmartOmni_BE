package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.TiktokProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TiktokInventorySubscriptionService {
    private static final String PATH = "/event/202309/webhooks";
    private static final String TOPIC = "INVENTORY_CHANGED_BY_SHOP";

    private final TiktokShopApiExecutor api;
    private final TiktokProperties properties;
    private final ObjectMapper mapper;

    /** Idempotently ensure that inventory changes reach the configured callback. */
    public boolean ensureSubscribed(long connectionId) {
        String address = properties.getInventoryWebhookUrl();
        if (address == null || address.isBlank()) return false;
        URI uri = URI.create(address);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("TikTok inventory webhook URL must be absolute HTTP(S)");
        }
        JsonNode existing = api.call(connectionId, "GET", PATH, Map.of(), null);
        for (JsonNode hook : existing.path("webhooks")) {
            if (TOPIC.equals(hook.path("event_type").asText()) && address.equals(hook.path("address").asText())) {
                return true;
            }
        }
        try {
            api.call(connectionId, "PUT", PATH, Map.of(),
                    mapper.writeValueAsString(Map.of("event_type", TOPIC, "address", address)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize TikTok inventory subscription", e);
        }
        return true;
    }
}
