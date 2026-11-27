package com.smartomni.order.dto.loyalty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** A TikTok Seller Center code batch as reported by the integration service (ONGOING codes of one title and discount). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TiktokVoucherBatchDto {
    private String title;
    private String discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal minOrderAmount;
    private Instant startTime;
    private Instant endTime;
    private int ongoingCodes;
    private int freeCodes;
}
