package com.smartomni.order.dto.loyalty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Replaces the tier milestones of one shop. Tiers with a campaignId are updated; tiers left out are closed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveLoyaltyTiersRequest {
    private Long connectionId;
    private List<LoyaltyTierSetupDto.Tier> tiers;
}
