package com.smartomni.integration.dto.tiktok;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A Seller Center code batch as SmartOmni sees it: the ONGOING codes sharing one title and one discount.
 * TikTok returns no batch id, so codes are grouped by title and discount.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TiktokVoucherBatchDto {

    private String title;
    /** PERCENT_OFF or AMOUNT_OFF. */
    private String discountType;
    /** Percent for PERCENT_OFF, VND for AMOUNT_OFF. */
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal minOrderAmount;
    /** Earliest start and earliest end among the batch's codes. */
    private Instant startTime;
    private Instant endTime;
    /** ONGOING codes of the batch on TikTok. */
    private int ongoingCodes;
    /** ONGOING codes not handed out by SmartOmni and not known to be used. */
    private int freeCodes;
}
