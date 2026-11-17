package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.dto.tiktok.CancellationSearchRequest;
import com.smartomni.integration.dto.tiktok.CancellationSearchResponse;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client for TikTok Shop Order Cancellation APIs routed through {@link TiktokShopApiExecutor}.
 * Matches TikTok Simulator develop ({@code /return_refund/202309/cancellations/*}).
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
        Map<String, String> query = new LinkedHashMap<>();
        int pageSize = (request != null && request.getPageSize() != null)
                ? Math.max(1, Math.min(request.getPageSize(), 50))
                : 20;
        query.put("page_size", Integer.toString(pageSize));
        if (request != null && request.getPageToken() != null && !request.getPageToken().isBlank()) {
            query.put("page_token", request.getPageToken().trim());
        }

        ObjectNode body = mapper.createObjectNode();
        if (request != null) {
            if (request.getCancelStatus() != null && !request.getCancelStatus().isBlank()) {
                body.put("cancel_status", request.getCancelStatus().trim());
            }
            if (request.getOrderId() != null && !request.getOrderId().isBlank()) {
                ArrayNode orderIds = body.putArray("order_ids");
                orderIds.add(request.getOrderId().trim());
            }
            if (request.getCreateTimeFrom() != null) {
                body.put("create_time_ge", request.getCreateTimeFrom());
            }
            if (request.getCreateTimeTo() != null) {
                body.put("create_time_lt", request.getCreateTimeTo());
            }
        }

        JsonNode data = executor.call(connectionId, "POST", SEARCH_CANCELLATIONS_PATH, query, body.toString());
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
     * Seller approves a buyer cancellation request (POST /return_refund/202309/cancellations/{cancel_id}/approve).
     */
    public boolean approveCancellation(Long connectionId, String cancelId) {
        String path = "/return_refund/202309/cancellations/" + cancelId + "/approve";
        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), "{}");
        return data != null;
    }

    /**
     * Seller rejects a buyer cancellation request (POST /return_refund/202309/cancellations/{cancel_id}/reject).
     */
    public boolean rejectCancellation(Long connectionId, String cancelId, String rejectReason) {
        String path = "/return_refund/202309/cancellations/" + cancelId + "/reject";
        ObjectNode body = mapper.createObjectNode();
        body.put("reject_reason", rejectReason != null ? rejectReason : "ALREADY_SHIPPED");

        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), body.toString());
        return data != null;
    }
}
