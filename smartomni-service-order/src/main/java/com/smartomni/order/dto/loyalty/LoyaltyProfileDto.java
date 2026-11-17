package com.smartomni.order.dto.loyalty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyProfileDto {
    private Long customerId;
    private Long tenantId;
    private long totalPoints;
    private String tier;
    private String nextTier;
    private Long nextTierThreshold;
    private long pointsToNextTier;
}
