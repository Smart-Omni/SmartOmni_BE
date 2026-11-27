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
public class LoyaltyCampaignDto {
    private Long id;
    private Long tenantId;
    private Long connectionId;
    private String platform;
    private String shopName;
    private Long createdBy;
    private String name;
    private BigDecimal discountPercent;
    private Long requiredPoints;
    private Integer quantity;
    private Integer reservedCount;
    private Integer availableQuantity;
    private String provisioningMode;
    private String externalCouponId;
    /** TikTok: exact title of the Seller Center code batch the campaign hands codes from. */
    private String tiktokBatchTitle;
    private String status;
    private Instant startsAt;
    private Instant endsAt;
    private Instant createdAt;
    private Instant updatedAt;
}
