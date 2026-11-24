package com.smartomni.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO yêu cầu xử lý hoàn tiền / trả hàng (Return & Refund Management - Bước 4.2, Bước 5.2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessReturnRequest {

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("connection_id")
    private Long connectionId;

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

    @JsonProperty("reject_reason")
    private String rejectReason;

    @JsonProperty("return_action")
    private ReturnAction returnAction;

    @Builder.Default
    @JsonProperty("evidence_urls")
    private List<String> evidenceUrls = new ArrayList<>();

    @JsonProperty("refund_amount")
    private BigDecimal refundAmount;

    @JsonProperty("restock_required")
    private Boolean restockRequired;

    /** Marketplace update_time of this return state (epoch seconds); older events are ignored. Optional. */
    @JsonProperty("platform_update_time")
    private Long platformUpdateTime;

    /**
     * True when the return covers only some units of the order. The order service then records the return but
     * leaves the status of the whole order unchanged. Null/false keeps the previous behaviour.
     */
    @JsonProperty("partial_return")
    private Boolean partialReturn;

    @Builder.Default
    @JsonProperty("items")
    private List<ReturnItemDto> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReturnItemDto {
        @JsonProperty("sku_id")
        private Long skuId;
        @JsonProperty("external_item_id")
        private String externalItemId;
        @JsonProperty("external_model_id")
        private String externalModelId;
        @JsonProperty("name")
        private String name;
        @JsonProperty("quantity")
        private Integer quantity;
        @JsonProperty("refund_amount")
        private BigDecimal refundAmount;
    }

    public enum ReturnAction {
        AWAITING_INSPECTION,
        RECEIVE_APPROVE,
        RECEIVE_REJECT,
        RECEIVE_REJECTED,
        REFUND_ONLY,
        DISPUTE_CLAIM,
        DISPUTE_APPROVED
    }
}
