package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokWebhookType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the Events API subscriptions of a connection pointing at SmartOmni's webhook receiver: one
 * {@code PUT /event/202309/webhooks} per topic in {@link #TOPICS}, skipping topics already registered for the same
 * address. Idempotent, so the scheduler can call it repeatedly.
 */
@Service
@RequiredArgsConstructor
public class TiktokWebhookSubscriptionService {
    private static final String PATH = "/event/202309/webhooks";

    /** Topics SmartOmni handles (see TiktokWebhookController). */
    public static final List<TiktokWebhookType> TOPICS = List.of(
            TiktokWebhookType.ORDER_STATUS_CHANGE,
            TiktokWebhookType.PACKAGE_UPDATE,
            TiktokWebhookType.RETURN_STATUS_CHANGE,
            TiktokWebhookType.CANCELLATION_STATUS_CHANGE,
            TiktokWebhookType.SELLER_DEAUTHORIZATION,
            TiktokWebhookType.INVENTORY_CHANGED_BY_SHOP);

    private final TiktokShopApiExecutor api;
    private final TiktokProperties properties;
    private final ObjectMapper mapper;

    /**
     * @return false when no callback address is configured ({@code TIKTOK_WEBHOOK_URL}, falling back to
     *         {@code TIKTOK_INVENTORY_WEBHOOK_URL}); true once every topic is subscribed
     */
    public boolean ensureSubscribed(long connectionId) {
        String address = properties.resolveWebhookUrl();
        if (address == null || address.isBlank()) return false;
        URI uri = URI.create(address);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("TikTok webhook URL must be absolute HTTP(S)");
        }
        JsonNode existing = api.call(connectionId, "GET", PATH, Map.of(), null);
        Set<String> subscribed = new HashSet<>();
        for (JsonNode hook : existing.path("webhooks")) {
            if (address.equals(hook.path("address").asText())) {
                subscribed.add(hook.path("event_type").asText());
            }
        }
        for (TiktokWebhookType topic : TOPICS) {
            if (subscribed.contains(topic.topic())) continue;
            try {
                api.call(connectionId, "PUT", PATH, Map.of(),
                        mapper.writeValueAsString(Map.of("event_type", topic.topic(), "address", address)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Cannot serialize TikTok webhook subscription", e);
            }
        }
        return true;
    }
}
