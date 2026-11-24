package com.smartomni.integration.dto.tiktok;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiktokWebhookTypeTest {

    /**
     * Copy of {@code WEBHOOK_TYPE} in the simulator, {@code backend/src/webhooks/webhook-emitter.service.ts}
     * (Smartomni-Tiktok-Simulator, develop at 7f721af). Update both together.
     */
    private static final Map<String, Integer> SIMULATOR_WEBHOOK_TYPE = new LinkedHashMap<>();

    static {
        SIMULATOR_WEBHOOK_TYPE.put("ORDER_STATUS_CHANGE", 1);
        SIMULATOR_WEBHOOK_TYPE.put("INVENTORY_CHANGED_BY_SHOP", 71);
        SIMULATOR_WEBHOOK_TYPE.put("RECIPIENT_ADDRESS_UPDATE", 2);
        SIMULATOR_WEBHOOK_TYPE.put("PACKAGE_UPDATE", 3);
        SIMULATOR_WEBHOOK_TYPE.put("REVERSE_STATUS_UPDATE", 4);
        SIMULATOR_WEBHOOK_TYPE.put("PRODUCT_STATUS_CHANGE", 5);
        SIMULATOR_WEBHOOK_TYPE.put("SELLER_DEAUTHORIZATION", 6);
        SIMULATOR_WEBHOOK_TYPE.put("UPCOMING_AUTHORIZATION_EXPIRATION", 7);
        SIMULATOR_WEBHOOK_TYPE.put("PRODUCT_AUDIT_STATUS_CHANGE", 8);
        SIMULATOR_WEBHOOK_TYPE.put("CANCELLATION_STATUS_CHANGE", 11);
        SIMULATOR_WEBHOOK_TYPE.put("RETURN_STATUS_CHANGE", 12);
        SIMULATOR_WEBHOOK_TYPE.put("NEW_CONVERSATION", 13);
        SIMULATOR_WEBHOOK_TYPE.put("NEW_MESSAGE", 14);
        SIMULATOR_WEBHOOK_TYPE.put("DISPUTE_STATUS_CHANGE", 15);
    }

    @Test
    void matchesTheSimulatorTableExactly() {
        Map<String, Integer> ours = Arrays.stream(TiktokWebhookType.values())
                .collect(Collectors.toMap(TiktokWebhookType::topic, TiktokWebhookType::code));
        assertEquals(Map.copyOf(SIMULATOR_WEBHOOK_TYPE), ours);
    }

    @Test
    void resolvesCodesBothWays() {
        SIMULATOR_WEBHOOK_TYPE.forEach((topic, code) ->
                assertEquals(topic, TiktokWebhookType.fromCode(code).orElseThrow().topic()));
        assertTrue(TiktokWebhookType.fromCode(16).isEmpty());
        assertTrue(TiktokWebhookType.fromCode(null).isEmpty());
        assertEquals("TIKTOK_EVENT_TYPE_16", TiktokWebhookType.logName(16));
        assertEquals("UNKNOWN", TiktokWebhookType.logName(null));
    }
}
