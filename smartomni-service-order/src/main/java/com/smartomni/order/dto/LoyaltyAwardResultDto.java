package com.smartomni.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary report returned by the 15-day loyalty point award job and manual trigger (WF-02 Part B).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyAwardResultDto {

    private int ordersEvaluated;
    private int ordersAwarded;
    private long totalPointsAwarded;
    private int skippedCount;
}
