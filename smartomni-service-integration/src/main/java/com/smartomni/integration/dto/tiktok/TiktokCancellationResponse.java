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

    @JsonProperty("refund_amount")
    private BigDecimal refundAmount;

    @JsonProperty("refund_currency")
    private String refundCurrency;

    @JsonProperty("create_time")
    private Long createTime;

    @JsonProperty("update_time")
    private Long updateTime;
}
