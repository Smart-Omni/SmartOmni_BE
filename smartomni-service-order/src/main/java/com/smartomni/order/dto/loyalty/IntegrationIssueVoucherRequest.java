package com.smartomni.order.dto.loyalty;

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
public class IntegrationIssueVoucherRequest {
    private Long connectionId;
    private String requestKey;
    private String title;
    private BigDecimal discountPercent;
    private String provisioningMode;
    private String externalCouponId;
    private String batchTitle;
    private Instant startsAt;
    private Instant endsAt;
}
