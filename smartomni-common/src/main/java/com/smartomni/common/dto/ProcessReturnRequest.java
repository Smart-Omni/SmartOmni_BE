package com.smartomni.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO yêu cầu xử lý hoàn tiền / trả hàng (Return & Refund Management - Bước 4.2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessReturnRequest {

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("platform")
    private String platform;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("platform_order_id")
    private String platformOrderId;

    @JsonProperty("return_id")
    private String returnId;

    @JsonProperty("return_status")
    private String returnStatus;

    @JsonProperty("return_type")
    private String returnType;

    @JsonProperty("return_reason")
    private String returnReason;

    @JsonProperty("refund_amount")
    private BigDecimal refundAmount;

    @JsonProperty("restock_required")
    private Boolean restockRequired;
}
