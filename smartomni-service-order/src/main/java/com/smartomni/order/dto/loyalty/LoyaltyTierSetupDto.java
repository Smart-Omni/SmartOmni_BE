package com.smartomni.order.dto.loyalty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tier milestones of one connected marketplace shop. Each tier is a reward campaign of that shop.
 * TikTok hands out codes of the Seller Center code batch linked to the tier ({@code tiktokBatchTitle}, {@code import});
 * Shopee vouchers are created by SmartOmni through the Shopee voucher API ({@code api}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyTierSetupDto {
    private Long connectionId;
    private String platform;
    private String shopName;
    private String provisioningMode;
    private List<Tier> tiers;
    private List<Tier> suggested;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tier {
        private Long campaignId;
        private String name;
        private Long requiredPoints;
        private BigDecimal discountPercent;
        /** TikTok: exact title of the linked Seller Center code batch. */
        private String tiktokBatchTitle;
        private Integer quantity;
        private Integer availableQuantity;
        private java.time.Instant endsAt;
    }
}
