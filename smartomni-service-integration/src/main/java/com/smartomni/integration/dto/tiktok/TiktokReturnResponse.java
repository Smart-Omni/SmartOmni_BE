package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO ánh xạ thông tin yêu cầu hoàn hàng / trả hàng (Reverse Order / Return / Refund) từ TikTok Shop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiktokReturnResponse {

    @JsonProperty("return_id")
    private String returnId;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("return_status")
    private String returnStatus;

    @JsonProperty("return_reason")
    private String returnReason;

    @JsonProperty("return_reason_text")
    private String returnReasonText;

    @JsonProperty("tracking_number")
    private String trackingNumber;

    @JsonProperty("return_tracking_number")
    private String returnTrackingNumber;

    @JsonProperty("return_type")
    private String returnType;

    /** Object with string amounts; use {@link TiktokRefundAmount#total()} for {@code refund_total}. */
    @JsonProperty("refund_amount")
    private TiktokRefundAmount refundAmount;

    @JsonProperty("refund_currency")
    private String refundCurrency;

    @JsonProperty("return_role")
    private String returnRole;

    /** Who created the request (BUYER, SELLER, OPERATOR, SYSTEM). */
    @JsonProperty("role")
    private String role;

    @JsonProperty("arbitration_status")
    private String arbitrationStatus;

    @JsonProperty("create_time")
    private Long createTime;

    @JsonProperty("update_time")
    private Long updateTime;

    @JsonProperty("return_line_items")
    private List<ReturnLineItem> returnLineItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReturnLineItem {
        @JsonProperty("return_item_id")
        @com.fasterxml.jackson.annotation.JsonAlias("return_line_item_id")
        private String returnItemId;

        @JsonProperty("order_line_item_id")
        private String orderLineItemId;

        @JsonProperty("product_id")
        private String productId;

        @JsonProperty("sku_id")
        private String skuId;

        @JsonProperty("seller_sku")
        private String sellerSku;

        @JsonProperty("quantity")
        private Integer quantity;

        @JsonProperty("product_name")
        private String productName;

        @JsonProperty("refund_amount")
        private TiktokRefundAmount refundAmount;
    }
}
