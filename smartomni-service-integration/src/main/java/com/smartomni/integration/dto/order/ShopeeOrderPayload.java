package com.smartomni.integration.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Normalized order event DTO published to RabbitMQ (compatible with smartomni-service-order's WebhookOrderPayload).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopeeOrderPayload {

    private Long tenantId;
    private String platform; // "SHOPEE"
    private String platformOrderId; // order_sn
    private String status; // Canonical status: PENDING, TO_SHIP, SHIPPED, DELIVERING, COMPLETED, CANCELLED, RETURNED
    private String rawShopeeStatus; // UNPAID, READY_TO_SHIP, etc.
    private String customerName;
    private String customerPhone;
    private String shippingAddress;
    private BigDecimal totalAmount;
    private String currency;
    private Long shipByDate;
    private Long createTime;
    private List<OrderItemPayload> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemPayload {
        private Long skuId;
        private String skuCode;
        private String externalSkuId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}
