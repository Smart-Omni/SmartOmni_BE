package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request DTO for issuing a TikTok Shop voucher (WF-02 Part A).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateTiktokVoucherRequest {

    private String title;

    @JsonAlias({"discount_type"})
    private String discountType;

    @JsonAlias({"discount_value"})
    private BigDecimal discountValue;

    @JsonAlias({"max_discount_amount"})
    private BigDecimal maxDiscountAmount;

    @JsonAlias({"min_order_amount", "min_spend_amount"})
    private BigDecimal minOrderAmount;

    @JsonAlias({"usage_limit"})
    private Integer usageLimit;

    private String code;

    @JsonAlias({"customer_id"})
    private String customerId;

    @JsonAlias({"start_time", "starts_at"})
    private Instant startTime;

    @JsonAlias({"end_time", "ends_at"})
    private Instant endTime;

    @JsonAlias({"idempotency_key"})
    private String idempotencyKey;
}
