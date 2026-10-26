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
