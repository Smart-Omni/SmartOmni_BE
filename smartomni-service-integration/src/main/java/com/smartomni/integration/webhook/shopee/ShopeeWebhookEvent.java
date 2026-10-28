package com.smartomni.integration.webhook.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

/**
 * Parsed payload representation of a push webhook notification from Shopee Open API v2 / Simulator.
 *
 * <p>Shopee Webhook payload format:
 * <pre>
 * {
 *   "code": 3,
 *   "shop_id": 727720655,
 *   "partner_id": 1001141,
 *   "timestamp": 1714000500,
 *   "data": {
 *     "ordersn": "241027SP1042",
 *     "status": "READY_TO_SHIP",
 *     "update_time": 1714000500
 *   }
 * }
 * </pre>
 */
@Value
@Builder
public class ShopeeWebhookEvent {

    int code;
    Long shopId;
    Long partnerId;
    Long timestamp;
    JsonNode data;

    public String orderSn() {
        if (data == null) {
            return null;
        }
        if (data.hasNonNull("ordersn")) {
            return data.get("ordersn").asText();
        }
        if (data.hasNonNull("order_sn")) {
            return data.get("order_sn").asText();
        }
        return null;
    }

    public String orderStatus() {
        if (data == null) {
            return null;
        }
        if (data.hasNonNull("status")) {
            return data.get("status").asText();
        }
        return null;
    }

    public String returnSn() {
        if (data == null) {
            return null;
        }
        if (data.hasNonNull("return_sn")) {
            return data.get("return_sn").asText();
        }
        if (data.hasNonNull("returnsn")) {
            return data.get("returnsn").asText();
        }
        return null;
    }

    public String trackingNo() {
        if (data == null) {
            return null;
        }
        if (data.hasNonNull("tracking_no")) {
            return data.get("tracking_no").asText();
        }
        if (data.hasNonNull("trackingno")) {
            return data.get("trackingno").asText();
        }
        return null;
    }
}
