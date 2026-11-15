package com.smartomni.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateManualOrderRequest {

    @JsonProperty("platform")
    private String platform;

    @JsonProperty("platform_order_id")
    private String platformOrderId;

    @JsonProperty("customer_name")
    private String customerName;

    @JsonProperty("customer_phone")
    private String customerPhone;

    @JsonProperty("shipping_address")
    private String shippingAddress;

    @JsonProperty("shipping_method")
    private String shippingMethod;

    @JsonProperty("total_amount")
    private BigDecimal totalAmount;

    @NotEmpty(message = "Order must contain at least one item")
    private List<ManualOrderItem> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManualOrderItem {
        @JsonProperty("sku_id")
        private Long skuId;

        @JsonProperty("quantity")
        private Integer quantity;

        @JsonProperty("unit_price")
        private BigDecimal unitPrice;

        @JsonProperty("image_url")
        private String imageUrl;
    }
}
