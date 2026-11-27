package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.LoyaltyTierSetupDto;
import com.smartomni.order.dto.loyalty.TiktokVoucherBatchDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoyaltyTierServiceTest {

    private static LoyaltyTierSetupDto.Tier tier(String name, Long points, String percent) {
        return tier(name, points, percent, "Loyalty " + name + " " + percent + "%");
    }

    private static LoyaltyTierSetupDto.Tier tier(String name, Long points, String percent, String batch) {
        return LoyaltyTierSetupDto.Tier.builder().name(name).requiredPoints(points)
                .discountPercent(percent == null ? null : new BigDecimal(percent)).tiktokBatchTitle(batch).build();
    }

    private static TiktokVoucherBatchDto batch(String title, String type, String value, int free, long end) {
        return TiktokVoucherBatchDto.builder().title(title).discountType(type).discountValue(new BigDecimal(value))
                .freeCodes(free).ongoingCodes(free).endTime(Instant.ofEpochSecond(end)).build();
    }

    @Test
    void suggestionsAreGoldPlatinumDiamond() {
        assertThat(LoyaltyTierService.SUGGESTED).extracting(LoyaltyTierSetupDto.Tier::getName)
                .containsExactly("Gold", "Platinum", "Diamond");
        assertThat(LoyaltyTierService.SUGGESTED).extracting(LoyaltyTierSetupDto.Tier::getRequiredPoints)
                .containsExactly(500L, 1000L, 1500L);
        assertThat(LoyaltyTierService.SUGGESTED).extracting(t -> t.getDiscountPercent().intValue())
                .containsExactly(10, 20, 30);
    }

    @Test
    void validTiersAreTrimmedAndSortedByPoints() {
        List<LoyaltyTierSetupDto.Tier> clean = LoyaltyTierService.validate(List.of(
                tier(" Diamond ", 1500L, "30", " Loyalty Diamond 30% "), tier("Gold", 500L, "10"),
                tier("Platinum", 1000L, "20")), "tiktok_shop");
        assertThat(clean).extracting(LoyaltyTierSetupDto.Tier::getName).containsExactly("Gold", "Platinum", "Diamond");
        assertThat(clean.get(2).getTiktokBatchTitle()).isEqualTo("Loyalty Diamond 30%");
    }

    @Test
    void rejectsBadTiers() {
        assertInvalid(List.of(), "tiktok_shop");
        assertInvalid(List.of(tier("Gold", 500L, "10"), tier("gold", 600L, "12")), "tiktok_shop");
        assertInvalid(List.of(tier("Gold", 500L, "10"), tier("Platinum", 500L, "20")), "tiktok_shop");
        assertInvalid(List.of(tier("Gold", 0L, "10")), "tiktok_shop");
        assertInvalid(List.of(tier("Gold", 500L, "0")), "tiktok_shop");
        assertInvalid(List.of(tier("Gold", 500L, "101")), "tiktok_shop");
        assertInvalid(List.of(tier(" ", 500L, "10")), "tiktok_shop");
    }

    @Test
    void percentMustBeWholeOnBothMarketplaces() {
        assertInvalid(List.of(tier("Gold", 500L, "10.5")), "shopee");
        assertInvalid(List.of(tier("Gold", 500L, "10.5")), "tiktok_shop");
        assertThat(LoyaltyTierService.validate(List.of(tier("Gold", 500L, "10.00")), "shopee")).hasSize(1);
        assertThat(LoyaltyTierService.validate(List.of(tier("Gold", 500L, "10.00")), "tiktok_shop")).hasSize(1);
    }

    @Test
    void tiktokTiersNeedTheirOwnBatch() {
        assertInvalid(List.of(tier("Gold", 500L, "10", " ")), "tiktok_shop");
        assertInvalid(List.of(tier("Gold", 500L, "10", "Shared"), tier("Platinum", 1000L, "20", "shared ")), "tiktok_shop");
        // Shopee ignores the batch
        assertThat(LoyaltyTierService.validate(List.of(tier("Gold", 500L, "10", "x")), "shopee").get(0)
                .getTiktokBatchTitle()).isNull();
    }

    @Test
    void linkTakesFreeCodesAndEarliestEndOfTheBatchGivingTheTierPercent() {
        List<TiktokVoucherBatchDto> batches = List.of(
                batch("Loyalty Gold 10%", "PERCENT_OFF", "10.00", 7, 9000),
                batch("loyalty gold 10%", "PERCENT_OFF", "10", 3, 8000),
                batch("Loyalty Gold 10% Plus", "PERCENT_OFF", "10", 50, 1000));

        LoyaltyTierService.BatchLink link = LoyaltyTierService.linkBatch(tier("Gold", 500L, "10"), batches);

        assertThat(link.freeCodes()).isEqualTo(10);
        assertThat(link.endTime()).isEqualTo(Instant.ofEpochSecond(8000));
    }

    @Test
    void linkRejectsMissingBatchAndAnotherPercent() {
        List<TiktokVoucherBatchDto> batches = List.of(batch("Loyalty Gold", "PERCENT_OFF", "15", 5, 9000));

        assertThatThrownBy(() -> LoyaltyTierService.linkBatch(tier("Gold", 500L, "10", "Loyalty Silver"), batches))
                .hasMessageContaining("No ongoing TikTok code batch titled 'Loyalty Silver'");
        assertThatThrownBy(() -> LoyaltyTierService.linkBatch(tier("Gold", 500L, "10", "Loyalty Gold"), batches))
                .isInstanceOf(BusinessException.class)
                .hasMessage("TikTok batch 'Loyalty Gold' gives 15%, Gold needs 10%");
    }

    @Test
    void provisioningModeFollowsTheMarketplace() {
        assertThat(LoyaltyTierService.modeFor("shopee")).isEqualTo("api");
        assertThat(LoyaltyTierService.modeFor("tiktok_shop")).isEqualTo("import");
    }

    private static void assertInvalid(List<LoyaltyTierSetupDto.Tier> tiers, String platform) {
        assertThatThrownBy(() -> LoyaltyTierService.validate(tiers, platform))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("INVALID_TIERS"));
    }
}
