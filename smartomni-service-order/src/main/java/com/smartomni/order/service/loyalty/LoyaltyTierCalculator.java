package com.smartomni.order.service.loyalty;

import com.smartomni.order.dto.loyalty.LoyaltyProfileDto;

/**
 * Computes customer loyalty tier and next-tier thresholds based on cumulative earned points:
 * Standard (&lt; 500), Silver (&gt;= 500), Platinum (&gt;= 1,000), Diamond (&gt;= 1,500).
 */
public final class LoyaltyTierCalculator {

    public static final long SILVER_THRESHOLD = 500L;
    public static final long PLATINUM_THRESHOLD = 1000L;
    public static final long DIAMOND_THRESHOLD = 1500L;

    private LoyaltyTierCalculator() {
    }

    public static LoyaltyProfileDto buildProfile(Long customerId, Long tenantId, long totalPoints) {
        long safePoints = Math.max(0L, totalPoints);
        String tier;
        String nextTier;
        Long nextThreshold;
        long pointsToNext;

        if (safePoints >= DIAMOND_THRESHOLD) {
            tier = "Diamond";
            nextTier = null;
            nextThreshold = null;
            pointsToNext = 0L;
        } else if (safePoints >= PLATINUM_THRESHOLD) {
            tier = "Platinum";
            nextTier = "Diamond";
            nextThreshold = DIAMOND_THRESHOLD;
            pointsToNext = DIAMOND_THRESHOLD - safePoints;
        } else if (safePoints >= SILVER_THRESHOLD) {
            tier = "Silver";
            nextTier = "Platinum";
            nextThreshold = PLATINUM_THRESHOLD;
            pointsToNext = PLATINUM_THRESHOLD - safePoints;
        } else {
            tier = "Standard";
            nextTier = "Silver";
            nextThreshold = SILVER_THRESHOLD;
            pointsToNext = SILVER_THRESHOLD - safePoints;
        }

        return LoyaltyProfileDto.builder()
                .customerId(customerId)
                .tenantId(tenantId)
                .totalPoints(safePoints)
                .tier(tier)
                .nextTier(nextTier)
                .nextTierThreshold(nextThreshold)
                .pointsToNextTier(pointsToNext)
                .build();
    }
}
