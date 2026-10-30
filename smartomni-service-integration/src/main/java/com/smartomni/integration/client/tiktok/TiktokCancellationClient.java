package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Client for TikTok Shop Order Cancellation APIs routed through {@link TiktokShopApiExecutor}.
 * Supports seller order cancellation and buyer cancellation request approvals/rejections.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokCancellationClient {

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    /**
     * Seller actively cancels an order on TikTok Shop (POST /order/202309/orders/{order_id}/cancel).
     */
    public boolean cancelOrder(Long connectionId, String orderId, String cancelReason) {
        String path = "/order/202309/orders/" + orderId + "/cancel";
        ObjectNode body = mapper.createObjectNode();
        body.put("cancel_reason", cancelReason != null ? cancelReason : "OUT_OF_STOCK");

        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), body.toString());
        return data != null;
    }

    /**
     * Seller approves a buyer cancellation request (POST /order/202309/cancellations/{cancel_id}/approve).
     */
    public boolean approveCancellation(Long connectionId, String cancelId) {
        String path = "/order/202309/cancellations/" + cancelId + "/approve";
        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), "{}");
        return data != null;
    }

    /**
     * Seller rejects a buyer cancellation request (POST /order/202309/cancellations/{cancel_id}/reject).
     */
    public boolean rejectCancellation(Long connectionId, String cancelId, String rejectReason) {
        String path = "/order/202309/cancellations/" + cancelId + "/reject";
        ObjectNode body = mapper.createObjectNode();
        body.put("reject_reason", rejectReason != null ? rejectReason : "SELLER_REJECT");

        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), body.toString());
        return data != null;
    }
}

