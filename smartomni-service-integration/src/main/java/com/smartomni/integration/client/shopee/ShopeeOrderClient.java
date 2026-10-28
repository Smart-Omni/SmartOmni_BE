package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Client for Shopee Order and Fulfillment APIs routed through {@link ShopeeShopApiExecutor}.
 */
@Component
public class ShopeeOrderClient {

    private final ShopeeShopApiExecutor executor;
    private final ObjectMapper mapper;

    public ShopeeOrderClient(ShopeeShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public record OrderSummary(String orderSn, String orderStatus) {}

    public record OrderListResult(List<OrderSummary> orders, boolean more, String nextCursor) {}

    public record RecipientAddress(
            String name,
            String phone,
            String fullAddress,
            String city,
            String district,
            String state,
            String zipcode
    ) {}

    public record OrderItemDetail(
            long itemId,
            String itemName,
            String itemSku,
            long modelId,
            String modelName,
            String modelSku,
            int quantityPurchased,
            BigDecimal originalPrice,
            BigDecimal discountedPrice,
            long orderItemId
    ) {}

    public record ShopeeOrderDetail(
            String orderSn,
            String orderStatus,
            String currency,
            BigDecimal totalAmount,
            String buyerUsername,
            Long buyerUserId,
            RecipientAddress recipientAddress,
            List<OrderItemDetail> items,
            Long shipByDate,
            Long createTime,
            Long updateTime,
            String cancelBy,
            String cancelReason,
            String shippingCarrier,
            String trackingNo
    ) {}

    /**
     * Retrieves paginated order list (GET /api/v2/order/get_order_list).
     */
    public OrderListResult getOrderList(Long connectionId, Long timeFrom, Long timeTo,
                                       int pageSize, String cursor, String orderStatus) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("time_range_field", "create_time");
        if (timeFrom != null) query.put("time_from", String.valueOf(timeFrom));
        if (timeTo != null) query.put("time_to", String.valueOf(timeTo));
        query.put("page_size", String.valueOf(Math.min(pageSize, 50)));
        if (cursor != null && !cursor.isBlank()) query.put("cursor", cursor);
        if (orderStatus != null && !orderStatus.isBlank()) query.put("order_status", orderStatus);

        JsonNode data = executor.call(connectionId, "GET", "/api/v2/order/get_order_list", query, null);

        List<OrderSummary> summaries = new ArrayList<>();
        JsonNode listNode = data.path("order_list");
        if (listNode.isArray()) {
            for (JsonNode o : listNode) {
                String sn = o.path("order_sn").asText("");
                String st = o.path("order_status").asText("");
                if (!sn.isBlank()) {
                    summaries.add(new OrderSummary(sn, st));
                }
            }
        }
        boolean more = data.path("more").asBoolean(false);
        String nextCursor = data.path("next_cursor").asText("");
        return new OrderListResult(summaries, more, nextCursor);
    }

    /**
     * Retrieves full order details for given order SNs (GET /api/v2/order/get_order_detail).
     */
    public List<ShopeeOrderDetail> getOrderDetail(Long connectionId, List<String> orderSnList) {
        if (orderSnList == null || orderSnList.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("order_sn_list", String.join(",", orderSnList));
        query.put("response_optional_fields", "buyer_user_id,buyer_username,recipient_address,item_list,ship_by_date,package_list");

        JsonNode data = executor.call(connectionId, "GET", "/api/v2/order/get_order_detail", query, null);
        List<ShopeeOrderDetail> result = new ArrayList<>();

        JsonNode orderArr = data.path("order_list");
        if (orderArr.isArray()) {
            for (JsonNode o : orderArr) {
                result.add(parseOrderDetail(o));
            }
        }
        return result;
    }

    /**
     * Cancels an order on Shopee (POST /api/v2/order/cancel_order).
     */
    public boolean cancelOrder(Long connectionId, String orderSn, String cancelReason) {
        ObjectNode body = mapper.createObjectNode();
        body.put("order_sn", orderSn);
        body.put("cancel_reason", cancelReason != null ? cancelReason : "OUT_OF_STOCK");

        JsonNode data = executor.call(connectionId, "POST", "/api/v2/order/cancel_order", Collections.emptyMap(), body.toString());
        return data != null;
    }

    /**
     * Accepts or rejects buyer cancellation request (POST /api/v2/order/handle_buyer_cancellation).
     * @param operation "ACCEPT" or "REJECT"
     */
    public boolean handleBuyerCancellation(Long connectionId, String orderSn, String operation) {
        ObjectNode body = mapper.createObjectNode();
        body.put("order_sn", orderSn);
        body.put("operation", operation);

        JsonNode data = executor.call(connectionId, "POST", "/api/v2/order/handle_buyer_cancellation", Collections.emptyMap(), body.toString());
        return data != null;
    }

    private ShopeeOrderDetail parseOrderDetail(JsonNode o) {
        String sn = o.path("order_sn").asText("");
        String status = o.path("order_status").asText("");
        String currency = o.path("currency").asText("VND");
        BigDecimal totalAmount = BigDecimal.valueOf(o.path("total_amount").asDouble(0.0));
        String buyerUsername = o.path("buyer_username").asText(null);
        Long buyerUserId = o.hasNonNull("buyer_user_id") ? o.path("buyer_user_id").asLong() : null;

        JsonNode addrNode = o.path("recipient_address");
        RecipientAddress address = new RecipientAddress(
                addrNode.path("name").asText(null),
                addrNode.path("phone").asText(null),
                addrNode.path("full_address").asText(null),
                addrNode.path("city").asText(null),
                addrNode.path("district").asText(null),
                addrNode.path("state").asText(null),
                addrNode.path("zipcode").asText(null)
        );

        List<OrderItemDetail> items = new ArrayList<>();
        JsonNode itemsArr = o.path("item_list");
        if (itemsArr.isArray()) {
            for (JsonNode it : itemsArr) {
                items.add(new OrderItemDetail(
                        it.path("item_id").asLong(),
                        it.path("item_name").asText(""),
                        it.path("item_sku").asText(""),
                        it.path("model_id").asLong(0),
                        it.path("model_name").asText(""),
                        it.path("model_sku").asText(""),
                        it.path("model_quantity_purchased").asInt(1),
                        BigDecimal.valueOf(it.path("model_original_price").asDouble(0.0)),
                        BigDecimal.valueOf(it.path("model_discounted_price").asDouble(0.0)),
                        it.path("order_item_id").asLong(0)
                ));
            }
        }

        Long shipByDate = o.hasNonNull("ship_by_date") ? o.path("ship_by_date").asLong() : null;
        Long createTime = o.hasNonNull("create_time") ? o.path("create_time").asLong() : null;
        Long updateTime = o.hasNonNull("update_time") ? o.path("update_time").asLong() : null;
        String cancelBy = o.path("cancel_by").asText(null);
        String cancelReason = o.path("cancel_reason").asText(null);
        String shippingCarrier = o.path("shipping_carrier").asText(null);

        String trackingNo = null;
        JsonNode pkgArr = o.path("package_list");
        if (pkgArr.isArray() && !pkgArr.isEmpty()) {
            trackingNo = pkgArr.get(0).path("tracking_number").asText(null);
            if (shippingCarrier == null) {
                shippingCarrier = pkgArr.get(0).path("shipping_carrier").asText(null);
            }
        }

        return new ShopeeOrderDetail(
                sn, status, currency, totalAmount, buyerUsername, buyerUserId, address,
                items, shipByDate, createTime, updateTime, cancelBy, cancelReason, shippingCarrier, trackingNo
        );
    }
}
