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
    private BigDecimal discountPercent;
    private String provisioningMode;
    private String externalCouponId;
    private Instant startsAt;
    private Instant endsAt;
}
