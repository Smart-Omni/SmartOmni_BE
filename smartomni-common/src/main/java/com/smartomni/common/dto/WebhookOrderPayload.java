package com.smartomni.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO chuẩn hóa payload đơn hàng từ Webhook / API của các sàn TMĐT (Shopee, TikTok Shop).
 * UC-32 / UC-33: Được sử dụng để đẩy vào RabbitMQ ('smartomni.order.exchange') và Order Service tiêu thụ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookOrderPayload {

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("platform")
    private String platform; // SHOPEE | TIKTOK_SHOP

    @JsonProperty("platform_order_id")
    private String platformOrderId;

    @JsonProperty("order_status")
    private String orderStatus;

    @JsonProperty("customer_name")
    private String customerName;

    @JsonProperty("customer_phone")
    private String customerPhone;

    @JsonProperty("shipping_address")
    private String shippingAddress;

    @JsonProperty("total_amount")
    private BigDecimal totalAmount;

    @JsonProperty("create_time")
    private Long createTime; // Timestamp thực tế trên sàn (seconds hoặc millis)

    @JsonProperty("created_at")
    private Instant createdAt;

    /**
     * Platform {@code update_time} of this order state (epoch seconds): TikTok order detail {@code update_time},
     * Shopee order detail / push code 3 {@code update_time}. Optional: when it is null on either side the order
     * service skips the staleness check.
     */
    @JsonProperty("platform_update_time")
    private Long platformUpdateTime;

    /**
     * How the order reached SmartOmni: {@code WEBHOOK} (default when absent) or {@code POLLING} (polling and manual
     * sync). Recorded as the order source; it never changes how the status is mapped.
     */
    @JsonProperty("source")
    private String source;

    // Marketplace delivery milestones (epoch seconds), TikTok order detail only for now; null when not happened yet.
    // Display and reconciliation only: they never decide the status, completed_at, the wallet or loyalty.

    /** TikTok {@code rts_time} (order moved to AWAITING_COLLECTION). */
    @JsonProperty("platform_rts_time")
    private Long platformRtsTime;

    /** TikTok {@code collection_time} (order moved to IN_TRANSIT). */
    @JsonProperty("platform_collection_time")
    private Long platformCollectionTime;

    /** TikTok {@code delivery_time} (order moved to DELIVERED); not the completion time. */
    @JsonProperty("platform_delivery_time")
    private Long platformDeliveryTime;

    /** TikTok {@code cancel_time} (order moved to CANCELLED). */
    @JsonProperty("platform_cancel_time")
    private Long platformCancelTime;

    /** Tracking number of the shipment, when the producer already knows it (TikTok polling); null otherwise. */
    @JsonProperty("tracking_number")
    private String trackingNumber;

    /** Carrier of that shipment; null when unknown. */
    @JsonProperty("shipping_provider")
    private String shippingProvider;

    /** Why the order needs manual reconciliation, e.g. {@code SKU_UNMAPPED}; null when nothing is wrong. */
    @JsonProperty("reconciliation_reason")
    private String reconciliationReason;

    @Builder.Default
    @JsonProperty("items")
    private List<OrderItemPayload> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItemPayload {

        @JsonProperty("sku_id")
        private Long skuId;

        @JsonProperty("external_sku_id")
        private String externalSkuId;

        @JsonProperty("seller_sku")
        private String sellerSku;

        @JsonProperty("product_name")
        private String productName;

        @JsonProperty("quantity")
        private Integer quantity;

        @JsonProperty("unit_price")
        private BigDecimal unitPrice;

        @JsonProperty("image_url")
        @com.fasterxml.jackson.annotation.JsonAlias("imageUrl")
        private String imageUrl;
    }
}
