package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO ánh xạ chi tiết đơn hàng (Order Detail) từ TikTok Shop Open Platform.
 * Thường trả về từ API /order/{version}/orders hoặc Webhook ORDER_STATUS_CHANGE data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TiktokOrderDetailResponse {

    @JsonProperty("order_id")
    @JsonAlias("id")
    private String orderId;

    @JsonProperty("order_status")
    @JsonAlias("status")
    private String orderStatus;

    @JsonProperty("payment")
    private Payment payment;

    @JsonProperty("recipient_address")
    private RecipientAddress recipientAddress;

    @JsonProperty("line_items")
    private List<LineItem> lineItems;

    @JsonProperty("package_id")
    private String packageId;

    @JsonProperty("package_list")
    @JsonAlias("packages")
    private List<OrderPackage> packageList;

    @JsonProperty("buyer_user_id")
    @JsonAlias("user_id")
    private String buyerUserId;

    @JsonProperty("buyer_email")
    private String buyerEmail;

    @JsonProperty("create_time")
    private Long createTime;

    @JsonProperty("update_time")
    private Long updateTime;

    @JsonProperty("paid_time")
    private Long paidTime;

    // Delivery milestones of Get Order Detail/List (simulator order.mapper.ts @ a830fce). Each is omitted from the JSON
    // until it happens. There is no completion time field: completion is the update_time of the change to COMPLETED.
    /** Set when the order moves to AWAITING_COLLECTION. */
    @JsonProperty("rts_time")
    private Long rtsTime;

    /** Set when the order moves to IN_TRANSIT. */
    @JsonProperty("collection_time")
    private Long collectionTime;

    /** Set when the order moves to DELIVERED. */
    @JsonProperty("delivery_time")
    private Long deliveryTime;

    /** Set when the order moves to CANCELLED. */
    @JsonProperty("cancel_time")
    private Long cancelTime;

    @JsonProperty("cancel_reason")
    private String cancelReason;

    @JsonProperty("cancel_user")
    private String cancelUser;

    @JsonProperty("tracking_number")
    private String trackingNumber;

    @JsonProperty("shipping_provider")
    private String shippingProvider;

    @JsonProperty("shipping_type")
    private String shippingType;

    @JsonProperty("fulfillment_type")
    private String fulfillmentType;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payment {
        @JsonProperty("currency")
        private String currency;

        @JsonProperty("sub_total")
        private BigDecimal subTotal;

        @JsonProperty("shipping_fee")
        private BigDecimal shippingFee;

        @JsonProperty("seller_discount")
        private BigDecimal sellerDiscount;

        @JsonProperty("platform_discount")
        private BigDecimal platformDiscount;

        @JsonProperty("total_amount")
        private BigDecimal totalAmount;

        @JsonProperty("original_total_product_price")
        private BigDecimal originalTotalProductPrice;

        @JsonProperty("tax")
        private BigDecimal tax;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecipientAddress {
        @JsonProperty("name")
        private String name;

        @JsonProperty("phone_number")
        private String phoneNumber;

        @JsonProperty("full_address")
        private String fullAddress;

        @JsonProperty("address_line1")
        private String addressLine1;

        @JsonProperty("address_line2")
        private String addressLine2;

        @JsonProperty("city")
        private String city;

        @JsonProperty("state")
        private String state;

        @JsonProperty("postal_code")
        private String postalCode;

        @JsonProperty("region_code")
        private String regionCode;

        @JsonProperty("district")
        private String district;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LineItem {
        @JsonProperty("id")
        private String id;

        @JsonProperty("product_id")
        private String productId;

        @JsonProperty("product_name")
        private String productName;

        @JsonProperty("sku_id")
        private String skuId;

        @JsonProperty("seller_sku")
        private String sellerSku;

        @JsonProperty("sku_name")
        private String skuName;

        @JsonProperty("sku_image")
        @com.fasterxml.jackson.annotation.JsonAlias({"image_url", "item_image"})
        private String skuImage;

        @JsonProperty("quantity")
        private Integer quantity;

        @JsonProperty("sale_price")
        private BigDecimal salePrice;

        @JsonProperty("original_price")
        private BigDecimal originalPrice;

        @JsonProperty("package_id")
        private String packageId;

        @JsonProperty("package_status")
        private String packageStatus;

        @JsonProperty("status")
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderPackage {
        @JsonProperty("package_id")
        @JsonAlias("id")
        private String packageId;

        @JsonProperty("package_status")
        private String packageStatus;

        @JsonProperty("status")
        private String status;
    }
}
