package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.dto.tiktok.CancellationSearchRequest;
import com.smartomni.integration.dto.tiktok.CancellationSearchResponse;
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

    public static final String SEARCH_CANCELLATIONS_PATH = "/return_refund/202309/cancellations/search";

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    /**
     * Lists buyer-initiated order cancellation requests (POST /return_refund/202309/cancellations/search).
     */
    public CancellationSearchResponse searchCancellations(Long connectionId, CancellationSearchRequest request) {
        String body;
        try {
            body = mapper.writeValueAsString(request != null ? request : new CancellationSearchRequest());
        } catch (JsonProcessingException e) {
            throw new TiktokApiException("Failed to serialize cancellation search request: " + e.getMessage(),
                    400, null, null, false);
        }
        JsonNode data = executor.call(connectionId, "POST", SEARCH_CANCELLATIONS_PATH, Map.of(), body);
        try {
            JsonNode source = data == null || data.isMissingNode() || data.isNull() ? mapper.createObjectNode() : data;
            return mapper.treeToValue(source, CancellationSearchResponse.class);
        } catch (JsonProcessingException e) {
            throw new TiktokApiException("TikTok response did not match CancellationSearchResponse",
                    200, null, null, false);
        }
    }

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

