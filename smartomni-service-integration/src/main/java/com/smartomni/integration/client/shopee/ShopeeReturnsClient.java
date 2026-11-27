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
            List<String> proofImages,
            Boolean needsLogistics,
            String negotiationStatus,
            String sellerCompensationStatus,
            BigDecimal reverseShippingFee,
            Long disputeDueDate,
            Long buyerReturnDueDate,
            Long expectedReturnDate,
            Long reverseDeliveredAt,
            Boolean isNfrExempt,
            Long createTime,
            Long updateTime,
            String trackingNumber,
            List<String> disputeReason,
            List<String> disputeTextReason,
            String validationType,
            String sellerProofStatus,
            BigDecimal offerAmount,
            String offerReason
    ) {}

    public record ReturnListResult(List<ReturnSummary> returnList, boolean more, int total) {}

    public record EnrichedShopeeReturn(
            String returnSn,
            String orderSn,
            String status,
            BigDecimal refundAmount,
            String currency,
            String reason,
            String textReason,
            String buyerUsername,
            Long createTime,
            Long updateTime,
            Long dueDate,
            Long disputeDueDate,
            Long buyerReturnDueDate,
            Long expectedReturnDate,
            Long reverseDeliveredAt,
            Boolean isNfrExempt,
            Boolean needsLogistics,
            String validationType,
            String trackingNumber,
            BigDecimal reverseShippingFee,
            String negotiationStatus,
            BigDecimal offerAmount,
            String offerReason,
            String sellerProofStatus,
            String compensationStatus,
            List<String> disputeReason,
            List<String> disputeTextReason,
            String rulingNotes,
            List<String> buyerProofImages,
            List<String> sellerProofImages,
            List<ReturnItem> items
    ) {}

    public record EnrichedReturnListResult(List<EnrichedShopeeReturn> returnList, boolean more, int total) {}

    public record ReturnItem(
            long itemId,
            long modelId,
            String name,
            int quantity,
            BigDecimal itemPrice,
            BigDecimal refundAmount,
            String variationSku
    ) {
        public ReturnItem(long itemId, long modelId, String name, int quantity, BigDecimal itemPrice, BigDecimal refundAmount) {
            this(itemId, modelId, name, quantity, itemPrice, refundAmount, "");
        }
    }

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
            String rulingNotes,
            Boolean needsLogistics,
            String compensationStatus,
            Long disputeDueDate,
            Long buyerReturnDueDate,
            Long expectedReturnDate,
            Long reverseDeliveredAt,
            Boolean isNfrExempt,
            String trackingNumber,
            String negotiationStatus,
            BigDecimal offerAmount,
            String offerReason,
            Long createTime,
            Long updateTime,
            String validationType,
            String sellerProofStatus,
            List<String> disputeReason,
            List<String> disputeTextReason
    ) {
        public ShopeeReturnDetail(
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
        ) {
            this(returnSn, orderSn, status, refundAmount, currency, reason, textReason, buyerUsername,
                    items, buyerProofImages, sellerProofImages, reverseShippingFee, rulingNotes,
                    null, null, null, null, null, null, false, "", null, null, "", null, null, null, null, List.of(), List.of());
        }
    }

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
                        nullableLong(r, "due_date"),
                        imgs,
                        r.hasNonNull("needs_logistics") ? r.path("needs_logistics").asBoolean() : null,
                        r.path("negotiation_status").asText(null),
                        r.path("seller_compensation_status").asText(null),
                        BigDecimal.valueOf(r.path("reverse_shipping_fee").asDouble(0.0)),
                        nullableLong(r, "dispute_due_date"),
                        nullableLong(r, "buyer_return_due_date"),
                        nullableLong(r, "expected_return_date"),
                        nullableLong(r, "reverse_delivered_at"),
                        r.path("is_nfr_exempt").asBoolean(false),
                        nullableLong(r, "create_time"),
                        nullableLong(r, "update_time"),
                        r.path("tracking_number").asText(""),
                        stringList(r.path("dispute_reason")),
                        stringList(r.path("dispute_text_reason")),
                        r.path("validation_type").asText(null),
                        r.path("seller_proof_status").asText(null),
                        r.hasNonNull("offer_amount") ? BigDecimal.valueOf(r.path("offer_amount").asDouble(0.0)) : null,
                        r.path("offer_reason").asText("")
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
                        BigDecimal.valueOf(it.path("refund_amount").asDouble(0.0)),
                        it.path("variation_sku").asText("")
                ));
            }
        }

        List<String> buyerProofs = stringList(data.path("proof_images"));
        List<String> sellerProofs = stringList(data.path("seller_proof_images"));

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
                data.path("ruling_notes").asText(""),
                data.hasNonNull("needs_logistics") ? data.path("needs_logistics").asBoolean() : null,
                data.hasNonNull("compensation_status")
                        ? data.path("compensation_status").asText(null)
                        : data.path("seller_compensation_status").asText(null),
                nullableLong(data, "dispute_due_date"),
                nullableLong(data, "buyer_return_due_date"),
                nullableLong(data, "expected_return_date"),
                nullableLong(data, "reverse_delivered_at"),
                data.path("is_nfr_exempt").asBoolean(false),
                data.path("tracking_number").asText(""),
                data.path("negotiation_status").asText(null),
                data.hasNonNull("offer_amount") ? BigDecimal.valueOf(data.path("offer_amount").asDouble(0.0)) : null,
                data.path("offer_reason").asText(""),
                nullableLong(data, "create_time"),
                nullableLong(data, "update_time"),
                data.path("validation_type").asText(null),
                data.path("seller_proof_status").asText(null),
                stringList(data.path("dispute_reason")),
                stringList(data.path("dispute_text_reason"))
        );
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isNull()) out.add(n.asText(""));
        }
        return out;
    }

    private static Long nullableLong(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asLong() : null;
    }

    /**
     * Merchant proposes a partial refund counter-offer (POST /api/v2/returns/offer).
     */
    public boolean offerReturn(Long connectionId, String returnSn, BigDecimal offerAmount, String offerReason) {
        ObjectNode body = mapper.createObjectNode();
        body.put("return_sn", returnSn);
        body.put("offer_amount", offerAmount != null ? offerAmount.doubleValue() : 0);
        if (offerReason != null && !offerReason.isBlank()) {
            body.put("offer_reason", offerReason);
        }
        JsonNode data = executor.call(connectionId, "POST", "/api/v2/returns/offer", Collections.emptyMap(), body.toString());
        return data != null;
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

    public record ConvertedImage(String url, String thumbnail) {}

    public record DisputeReasonOption(int disputeReasonId, String disputeName, String disputeRequirement) {}

    /**
     * Fetches official Shopee dispute reasons for a return (GET /api/v2/returns/get_return_dispute_reason).
     */
    public List<DisputeReasonOption> getReturnDisputeReasons(Long connectionId, String returnSn) {
        Map<String, String> query = Map.of("return_sn", returnSn);
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/returns/get_return_dispute_reason", query, null);

        List<DisputeReasonOption> list = new ArrayList<>();
        JsonNode arr = data.path("dispute_reason_list");
        if (arr.isArray() && !arr.isEmpty()) {
            for (JsonNode d : arr) {
                list.add(new DisputeReasonOption(
                        d.path("dispute_reason").asInt(),
                        d.path("dispute_name").asText(""),
                        d.path("dispute_requirement").asText("")
                ));
            }
            return list;
        }
        JsonNode fallback = data.path("dispute_reason");
        if (fallback.isArray()) {
            for (JsonNode d : fallback) {
                list.add(new DisputeReasonOption(
                        d.path("dispute_reason_id").asInt(),
                        d.path("dispute_reason_name").asText(""),
                        ""
                ));
            }
        }
        return list;
    }

    /**
     * Uploads an image to Shopee (POST /api/v2/returns/convert_image) and returns its {url, thumbnail}.
     */
    public ConvertedImage convertImage(Long connectionId, byte[] fileBytes, String filename, String contentType) {
        return convertImage(connectionId, null, fileBytes, filename, contentType);
    }

    public ConvertedImage convertImage(Long connectionId, String returnSn, byte[] fileBytes, String filename, String contentType) {
        Map<String, String> extra = (returnSn != null && !returnSn.isBlank())
                ? Map.of("return_sn", returnSn)
                : Collections.emptyMap();
        JsonNode data = executor.callMultipart(connectionId, "/api/v2/returns/convert_image", "upload_image", fileBytes, filename, contentType, extra);
        return new ConvertedImage(data.path("url").asText(null), data.path("thumbnail").asText(null));
    }

    /**
     * Uploads dispute proof (POST /api/v2/returns/upload_proof).
     */
    public boolean uploadProof(Long connectionId, String returnSn, String description, List<ConvertedImage> photos) {
        ObjectNode body = mapper.createObjectNode();
        body.put("return_sn", returnSn);
        if (description != null && !description.isBlank()) {
            body.put("description", description);
        }
        ArrayNode photoArr = body.putArray("photo");
        if (photos != null) {
            for (ConvertedImage p : photos) {
                ObjectNode node = mapper.createObjectNode();
                if (p.url() != null) node.put("url", p.url());
                if (p.thumbnail() != null) node.put("thumbnail", p.thumbnail());
                photoArr.add(node);
            }
        }
        JsonNode data = executor.call(connectionId, "POST", "/api/v2/returns/upload_proof", Collections.emptyMap(), body.toString());
        return data != null;
    }

    /**
     * Queries uploaded return/dispute proof (POST /api/v2/returns/query_proof).
     */
    public JsonNode queryProof(Long connectionId, String returnSn) {
        ObjectNode body = mapper.createObjectNode();
        body.put("return_sn", returnSn);
        return executor.call(connectionId, "POST", "/api/v2/returns/query_proof", Collections.emptyMap(), body.toString());
    }
}
