package com.smartomni.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalIssueVoucherRequest {
    private Long connectionId;
    private String requestKey;
    private String title;
    /** TikTok: exact title of the Seller Center code batch the campaign is linked to (falls back to title). */
    private String batchTitle;
    private BigDecimal discountPercent;
    private String provisioningMode;
    private String externalCouponId;
    private Instant startsAt;
    private Instant endsAt;
}
