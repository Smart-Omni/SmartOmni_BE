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
 * DTO representing detailed return & refund record for staff lookup and inspection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReturnDetailDto {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("order_id")
    private Long orderId;

    @JsonProperty("platform_order_id")
    private String platformOrderId;

    @JsonProperty("connection_id")
    private Long connectionId;

    @JsonProperty("platform")
    private String platform;

    @JsonProperty("external_return_id")
    private String externalReturnId;

    @JsonProperty("return_type")
    private String returnType;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("status")
    private String status;

    @JsonProperty("inspection_result")
    private String inspectionResult;

    @JsonProperty("restocked")
    private boolean restocked;

    @JsonProperty("return_tracking_number")
    private String returnTrackingNumber;

    @JsonProperty("video_evidence_url")
    private String videoEvidenceUrl;

    @JsonProperty("received_at")
    private Instant receivedAt;

    @JsonProperty("dispute_status")
    private String disputeStatus;

    @JsonProperty("refund_amount")
    private BigDecimal refundAmount;

    @Builder.Default
    @JsonProperty("items")
    private List<ReturnDetailItemDto> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReturnDetailItemDto {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("sku_id")
        private Long skuId;

        @JsonProperty("external_item_id")
        private String externalItemId;

        @JsonProperty("item_name")
        private String itemName;

        @JsonProperty("quantity_returned")
        private Integer quantityReturned;

        @JsonProperty("quantity_inspected")
        private Integer quantityInspected;

        @JsonProperty("refund_amount")
        private BigDecimal refundAmount;
    }
}
