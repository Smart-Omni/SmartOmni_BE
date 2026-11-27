package com.smartomni.order.dto.loyalty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * A customer's points (one wallet for every marketplace) and, per connected marketplace, the shop's tier milestones.
 * {@code tier}/{@code nextTier} summarise all marketplaces; {@code platforms} drives the progress bar.
 */
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
    @Builder.Default
    private List<PlatformProgress> platforms = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlatformProgress {
        private String platform;
        private Long connectionId;
        private String shopName;
        private String currentTier;
        private String nextTier;
        private Long nextTierThreshold;
        private long pointsToNextTier;
        private List<Milestone> milestones;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Milestone {
        private Long campaignId;
        private String name;
        private long requiredPoints;
        private BigDecimal discountPercent;
        private boolean reached;
        /** Vouchers still available to hand out for this milestone. */
        private int availableQuantity;
        /** The customer's latest request for this milestone, or null. */
        private Reward reward;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reward {
        private Long requestId;
        private String status;
        private String code;
        private String failureReason;
    }
}
