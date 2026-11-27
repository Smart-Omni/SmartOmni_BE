package com.smartomni.order.service.loyalty;

import com.smartomni.order.dto.loyalty.LoyaltyProfileDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoyaltyTierCalculatorTest {

    @Test
    void computesAllFourTiersAndNextThresholdsCorrectly() {
        LoyaltyProfileDto standard = LoyaltyTierCalculator.buildProfile(10L, 1L, 0L);
        assertThat(standard.getTier()).isEqualTo("Standard");
        assertThat(standard.getNextTier()).isEqualTo("Gold");
        assertThat(standard.getNextTierThreshold()).isEqualTo(500L);
        assertThat(standard.getPointsToNextTier()).isEqualTo(500L);

        LoyaltyProfileDto silver = LoyaltyTierCalculator.buildProfile(10L, 1L, 500L);
        assertThat(silver.getTier()).isEqualTo("Gold");
        assertThat(silver.getNextTier()).isEqualTo("Platinum");
        assertThat(silver.getNextTierThreshold()).isEqualTo(1000L);
        assertThat(silver.getPointsToNextTier()).isEqualTo(500L);

        LoyaltyProfileDto platinum = LoyaltyTierCalculator.buildProfile(10L, 1L, 1250L);
        assertThat(platinum.getTier()).isEqualTo("Platinum");
        assertThat(platinum.getNextTier()).isEqualTo("Diamond");
        assertThat(platinum.getNextTierThreshold()).isEqualTo(1500L);
        assertThat(platinum.getPointsToNextTier()).isEqualTo(250L);

        LoyaltyProfileDto diamond = LoyaltyTierCalculator.buildProfile(10L, 1L, 1500L);
        assertThat(diamond.getTier()).isEqualTo("Diamond");
        assertThat(diamond.getNextTier()).isNull();
        assertThat(diamond.getNextTierThreshold()).isNull();
        assertThat(diamond.getPointsToNextTier()).isZero();
    }
}
