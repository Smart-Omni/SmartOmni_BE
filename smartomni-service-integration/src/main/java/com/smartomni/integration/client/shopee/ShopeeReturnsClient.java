package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Client for Shopee Return, Refund, and Dispute Management APIs (UC-10, Phase 6).
 */
@Component
public class ShopeeReturnsClient {

    private final ShopeeShopApiExecutor executor;
    private final ObjectMapper mapper;

    public ShopeeReturnsClient(ShopeeShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public record ReturnSummary(
            String returnSn,
            String orderSn,
            String status,
            BigDecimal refundAmount,
            String currency,
            String reason,
            String textReason,
            Long dueDate,
            List<String> proofImages
    ) {}

    public record ReturnListResult(List<ReturnSummary> returnList, boolean more, int total) {}

    public record ReturnItem(
            long itemId,
            long modelId,
            String name,
            int quantity,
            BigDecimal itemPrice,
            BigDecimal refundAmount
    ) {}

    public record ShopeeReturnDetail(
            String returnSn,
            String orderSn,
            String status,
            BigDecimal refundAmount,
            String currency,
            String reason,
            String textReason,
            String buyerUsername,
            List<ReturnItem> items,
            List<String> buyerProofImages,
            List<String> sellerProofImages,
            BigDecimal reverseShippingFee,
            String rulingNotes
    ) {}

    /**
     * Retrieves paginated return requests (GET /api/v2/returns/get_return_list).
     */
    public ReturnListResult getReturnList(Long connectionId, int pageNo, int pageSize, String status) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page_no", String.valueOf(Math.max(1, pageNo)));
        query.put("page_size", String.valueOf(Math.min(pageSize, 50)));
        if (status != null && !status.isBlank()) {
            query.put("status", status);
        }

        JsonNode data = executor.call(connectionId, "GET", "/api/v2/returns/get_return_list", query, null);

        List<ReturnSummary> summaries = new ArrayList<>();
        JsonNode arr = data.path("return_list");
        if (arr.isMissingNode() || arr.isNull() || !arr.isArray()) {
            arr = data.path("return");
        }

        if (arr.isArray()) {
            for (JsonNode r : arr) {
                List<String> imgs = new ArrayList<>();
                JsonNode imgArr = r.path("image");
                if (imgArr.isArray()) {
                    for (JsonNode img : imgArr) imgs.add(img.asText());
                }

                summaries.add(new ReturnSummary(
                        r.path("return_sn").asText(""),
                        r.path("order_sn").asText(""),
                        r.path("status").asText(""),
                        BigDecimal.valueOf(r.path("refund_amount").asDouble(0.0)),
                        r.path("currency").asText("VND"),
                        r.path("reason").asText(""),
                        r.path("text_reason").asText(""),
                        r.hasNonNull("due_date") ? r.path("due_date").asLong() : null,
                        imgs
                ));
            }
        }

        boolean more = data.path("more").asBoolean(false);
        int total = data.path("total").asInt(summaries.size());
        return new ReturnListResult(summaries, more, total);
    }

    /**
     * Retrieves complete details of a return request (GET /api/v2/returns/get_return_detail).
     */
    public ShopeeReturnDetail getReturnDetail(Long connectionId, String returnSn) {
        Map<String, String> query = Map.of("return_sn", returnSn);
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/returns/get_return_detail", query, null);

        List<ReturnItem> items = new ArrayList<>();
        JsonNode itemArr = data.path("item");
        if (itemArr.isArray()) {
            for (JsonNode it : itemArr) {
                items.add(new ReturnItem(
                        it.path("item_id").asLong(),
                        it.path("model_id").asLong(0),
                        it.path("name").asText(""),
                        it.path("amount").asInt(1),
                        BigDecimal.valueOf(it.path("item_price").asDouble(0.0)),
                        BigDecimal.valueOf(it.path("refund_amount").asDouble(0.0))
                ));
            }
        }

        List<String> buyerProofs = new ArrayList<>();
        JsonNode bProofArr = data.path("proof_images");
        if (bProofArr.isArray()) {
            for (JsonNode p : bProofArr) buyerProofs.add(p.asText());
        }

        List<String> sellerProofs = new ArrayList<>();
        JsonNode sProofArr = data.path("seller_proof_images");
        if (sProofArr.isArray()) {
            for (JsonNode p : sProofArr) sellerProofs.add(p.asText());
        }

        return new ShopeeReturnDetail(
                data.path("return_sn").asText(returnSn),
                data.path("order_sn").asText(""),
                data.path("status").asText(""),
                BigDecimal.valueOf(data.path("refund_amount").asDouble(0.0)),
                data.path("currency").asText("VND"),
                data.path("reason").asText(""),
                data.path("text_reason").asText(""),
                data.path("user").path("username").asText(""),
                items,
                buyerProofs,
                sellerProofs,
                BigDecimal.valueOf(data.path("reverse_shipping_fee").asDouble(0.0)),
                data.path("ruling_notes").asText("")
        );
    }

    /**
     * Merchant confirms/accepts a return and refund request (POST /api/v2/returns/confirm).
     */
    public boolean confirmReturn(Long connectionId, String returnSn) {
        ObjectNode body = mapper.createObjectNode();
        body.put("return_sn", returnSn);

        JsonNode data = executor.call(connectionId, "POST", "/api/v2/returns/confirm", Collections.emptyMap(), body.toString());
        return data != null;
    }

    /**
     * Merchant disputes a return request with dispute reason and photo proofs (POST /api/v2/returns/dispute).
     */
    public boolean disputeReturn(Long connectionId, String returnSn, Integer disputeReasonId,
                                 String disputeTextReason, String email, List<String> images) {
        ObjectNode body = mapper.createObjectNode();
        body.put("return_sn", returnSn);
        body.put("dispute_reason_id", disputeReasonId != null ? disputeReasonId : 1);
        body.put("dispute_text_reason", disputeTextReason != null ? disputeTextReason : "Seller dispute proof submitted");
        if (email != null && !email.isBlank()) {
            body.put("email", email);
        }

        if (images != null && !images.isEmpty()) {
            ArrayNode imgArr = body.putArray("image");
            for (String img : images) {
                imgArr.add(img);
            }
        }

        JsonNode data = executor.call(connectionId, "POST", "/api/v2/returns/dispute", Collections.emptyMap(), body.toString());
        return data != null;
    }
}
