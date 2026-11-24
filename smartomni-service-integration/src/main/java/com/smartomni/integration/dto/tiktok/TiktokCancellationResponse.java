package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO representing a buyer-initiated order cancellation request from TikTok Shop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiktokCancellationResponse {

    @JsonProperty("cancel_id")
    private String cancelId;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("cancel_status")
    @JsonAlias({"status"})
    private String cancelStatus;

    @JsonProperty("cancel_reason")
    private String cancelReason;

    @JsonProperty("cancel_reason_text")
    private String cancelReasonText;

    @JsonProperty("cancel_type")
    private String cancelType;

    @JsonProperty("buyer_user_id")
    private String buyerUserId;

    /** Object with string amounts; use {@link TiktokRefundAmount#total()} for {@code refund_total}. */
    @JsonProperty("refund_amount")
    private TiktokRefundAmount refundAmount;

    /** Who created the request (BUYER, SELLER, OPERATOR, SYSTEM). */
    @JsonProperty("role")
    private String role;

    @JsonProperty("arbitration_status")
    private String arbitrationStatus;

    @JsonProperty("cancel_line_items")
    private java.util.List<CancelLineItem> cancelLineItems;

    @JsonProperty("refund_currency")
    private String refundCurrency;

    @JsonProperty("create_time")
    private Long createTime;

    @JsonProperty("update_time")
    private Long updateTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CancelLineItem {
        @JsonProperty("cancel_line_item_id")
        private String cancelLineItemId;

        @JsonProperty("order_line_item_id")
        private String orderLineItemId;

        @JsonProperty("sku_id")
        private String skuId;

        @JsonProperty("seller_sku")
        private String sellerSku;

        @JsonProperty("product_name")
        private String productName;

        @JsonProperty("refund_amount")
        private TiktokRefundAmount refundAmount;
    }
}
