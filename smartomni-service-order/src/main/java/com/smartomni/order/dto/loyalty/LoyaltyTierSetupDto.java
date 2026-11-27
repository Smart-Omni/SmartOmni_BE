package com.smartomni.order.dto.loyalty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tier milestones of one connected marketplace shop. Each tier is a reward campaign of that shop.
 * TikTok hands out codes of a Seller Center code batch whose title matches the tier name ({@code import});
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
        private Integer quantity;
        private Integer availableQuantity;
    }
}
