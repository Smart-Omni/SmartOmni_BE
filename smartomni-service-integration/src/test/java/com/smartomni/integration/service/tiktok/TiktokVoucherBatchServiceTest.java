package com.smartomni.integration.service.tiktok;

import com.smartomni.integration.dto.tiktok.TiktokVoucherBatchDto;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TiktokVoucherBatchServiceTest {

    private static TiktokVoucherDto code(String id, String title, String type, String value, long endEpoch) {
        return TiktokVoucherDto.builder().tiktokVoucherId(id).voucherCode("P" + id).title(title)
                .discountType(type).discountValue(new BigDecimal(value)).status("ONGOING")
                .startTime(Instant.ofEpochSecond(1000)).endTime(Instant.ofEpochSecond(endEpoch)).build();
    }

    @Test
    void groupsCodesByTitleAndDiscountAndCountsFreeOnes() {
        List<TiktokVoucherBatchDto> batches = TiktokVoucherBatchService.group(List.of(
                code("1", "Loyalty Gold 10%", "PERCENT_OFF", "10", 5000),
                code("2", "loyalty gold 10% ", "PERCENT_OFF", "10.00", 4000),
                code("3", "Loyalty Gold 10%", "PERCENT_OFF", "15", 5000),
                code("4", "Loyalty Diamond 30%", "PERCENT_OFF", "30", 5000),
                code("5", "Welcome", "AMOUNT_OFF", "20000", 5000)), Set.of("1", "4"));

        assertThat(batches).extracting(TiktokVoucherBatchDto::getTitle)
                .containsExactly("Loyalty Diamond 30%", "Loyalty Gold 10%", "Loyalty Gold 10%", "Welcome");
        TiktokVoucherBatchDto gold10 = batches.get(1);
        assertThat(gold10.getDiscountValue()).isEqualByComparingTo("10");
        assertThat(gold10.getOngoingCodes()).isEqualTo(2);
        assertThat(gold10.getFreeCodes()).isEqualTo(1);
        assertThat(gold10.getEndTime()).isEqualTo(Instant.ofEpochSecond(4000));
        assertThat(batches.get(2).getDiscountValue()).isEqualByComparingTo("15");
        assertThat(batches.get(2).getFreeCodes()).isZero();
    }

    @Test
    void titleAndPercentRules() {
        assertThat(TiktokVoucherBatchService.sameTitle(" Loyalty Gold ", "loyalty gold")).isTrue();
        assertThat(TiktokVoucherBatchService.sameTitle("Loyalty Gold Plus", "Loyalty Gold")).isFalse();
        TiktokVoucherDto ten = code("1", "x", "PERCENT_OFF", "10.00", 1);
        assertThat(TiktokVoucherBatchService.samePercent(ten, new BigDecimal("10"))).isTrue();
        assertThat(TiktokVoucherBatchService.samePercent(ten, new BigDecimal("20"))).isFalse();
        assertThat(TiktokVoucherBatchService.samePercent(code("2", "x", "AMOUNT_OFF", "10", 1), BigDecimal.TEN)).isFalse();
        assertThat(TiktokVoucherBatchService.describe(code("3", "x", "AMOUNT_OFF", "20000", 1))).isEqualTo("20000 VND off");
    }
}
