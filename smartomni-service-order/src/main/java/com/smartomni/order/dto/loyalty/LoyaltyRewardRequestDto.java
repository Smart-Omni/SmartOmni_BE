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
public class LoyaltyRewardRequestDto {
    private Long id;
    private Long tenantId;
    private Long campaignId;
    private String campaignName;
    private BigDecimal discountPercent;
    private Long customerId;
    private String requestKey;
    private String status;
    private String code;
    private String externalVoucherId;
    private String failureReason;
    private Instant requestedAt;
    private Instant issuedAt;
    private Instant updatedAt;
}
