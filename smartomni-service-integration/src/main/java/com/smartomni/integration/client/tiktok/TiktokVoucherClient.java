package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client calling TikTok Simulator's Coupon APIs on develop:
 * {@code POST /promotion/202406/coupons/search} and {@code GET /promotion/202406/coupons/{couponId}}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokVoucherClient {

    public static final String SEARCH_COUPONS_PATH = "/promotion/202406/coupons/search";
    public static final String COUPON_DETAIL_PATH = "/promotion/202406/coupons/";
    public static final String CREATE_COUPON_PATH = "/promotion/202406/coupons";

    private final TiktokShopApiExecutor executor;

    /**
     * Searches coupons from TikTok Simulator ({@code POST /promotion/202406/coupons/search}).
     */
    public List<TiktokVoucherDto> searchCoupons(Long connectionId) {
        JsonNode data = executor.call(connectionId, "POST", SEARCH_COUPONS_PATH, Map.of("page_size", "50"), "{}");
        List<TiktokVoucherDto> list = new ArrayList<>();
        if (data != null && data.has("coupons") && data.get("coupons").isArray()) {
            for (JsonNode node : data.get("coupons")) {
                TiktokVoucherDto parsed = parseCoupon(node);
                if (parsed != null) {
                    list.add(parsed);
                }
            }
        }
        return list;
    }

    /**
     * Fetches a single coupon detail ({@code GET /promotion/202406/coupons/{couponId}}).
     */
    public TiktokVoucherDto getCoupon(Long connectionId, String couponId) {
        JsonNode data = executor.call(connectionId, "GET", COUPON_DETAIL_PATH + couponId, Map.of(), null);
        if (data != null && data.has("coupon")) {
            return parseCoupon(data.get("coupon"));
        }
        return parseCoupon(data);
    }

    /**
     * Calls TikTok Shop coupon creation endpoint ({@code POST /promotion/202406/coupons}) when API mode is enabled.
     */
    public TiktokVoucherDto createCoupon(Long connectionId, String title, BigDecimal discountPercent,
                                         Instant startsAt, Instant endsAt, String requestKey) {
        long startSec = (startsAt != null ? startsAt : Instant.now()).getEpochSecond();
        long endSec = (endsAt != null ? endsAt : Instant.now().plusSeconds(30L * 24 * 3600)).getEpochSecond();
        String safeTitle = (title != null ? title.replace("\"", "") : "SmartOmni Loyalty Voucher");
        String body = String.format(
                "{\"title\":\"%s\",\"request_key\":\"%s\",\"discount\":{\"type\":\"PERCENT_OFF\",\"percentage\":\"%s\"},"
                        + "\"claim_duration\":{\"start_time\":%d,\"end_time\":%d},\"usage_limits\":{\"redemption_limit\":1}}",
                safeTitle,
                requestKey != null ? requestKey : "",
                discountPercent != null ? discountPercent.stripTrailingZeros().toPlainString() : "10",
                startSec,
                endSec);
        JsonNode data = executor.call(connectionId, "POST", CREATE_COUPON_PATH, Map.of(), body);
        if (data != null && data.has("coupon")) {
            return parseCoupon(data.get("coupon"));
        }
        return parseCoupon(data);
    }

    private TiktokVoucherDto parseCoupon(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String id = text(node, "id", null);
        if (id == null || id.isBlank()) {
            return null;
        }
        String title = text(node, "title", "TikTok Coupon " + id);
        String status = text(node, "status", "ONGOING");
        String code = text(node, "promo_code", null);

        JsonNode discount = node.path("discount");
        String discountType = text(discount, "type", "AMOUNT_OFF");
        BigDecimal discountValue = BigDecimal.ZERO;
        BigDecimal maxDiscount = null;
        if ("PERCENT_OFF".equalsIgnoreCase(discountType)) {
            if (discount.hasNonNull("percentage")) {
                discountValue = new BigDecimal(discount.get("percentage").asText("0"));
            }
            if (discount.hasNonNull("max_discount") && discount.get("max_discount").hasNonNull("amount")) {
                maxDiscount = new BigDecimal(discount.get("max_discount").get("amount").asText("0"));
            }
        } else if (discount.hasNonNull("reduction_amount") && discount.get("reduction_amount").hasNonNull("amount")) {
            discountValue = new BigDecimal(discount.get("reduction_amount").get("amount").asText("0"));
        }

        BigDecimal minOrder = null;
        JsonNode threshold = node.path("threshold");
        if ("MIN_SPEND".equalsIgnoreCase(text(threshold, "type", ""))
                && threshold.hasNonNull("min_spend")
                && threshold.get("min_spend").hasNonNull("amount")) {
            minOrder = new BigDecimal(threshold.get("min_spend").get("amount").asText("0"));
        }

        Integer usageLimit = node.path("usage_limits").hasNonNull("redemption_limit")
                ? node.path("usage_limits").get("redemption_limit").asInt()
                : null;
        Integer redeemedCount = node.path("usage_stats").hasNonNull("redeemed_count")
                ? node.path("usage_stats").get("redeemed_count").asInt()
                : 0;

        Instant startTime = fromUnixSeconds(node.path("claim_duration").path("start_time").asLong(0), Instant.now());
        Instant endTime = fromUnixSeconds(node.path("claim_duration").path("end_time").asLong(0),
                startTime.plusSeconds(30L * 24 * 3600));

        return TiktokVoucherDto.builder()
                .tiktokVoucherId(id)
                .voucherCode(code)
                .title(title)
                .discountType(discountType)
                .discountValue(discountValue)
                .maxDiscountAmount(maxDiscount)
                .minOrderAmount(minOrder)
                .usageLimit(usageLimit)
                .redeemedCount(redeemedCount)
                .startTime(startTime)
                .endTime(endTime)
                .status(status)
                .build();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? fallback : v.asText();
    }

    private static Instant fromUnixSeconds(long seconds, Instant fallback) {
        return seconds > 0 ? Instant.ofEpochSecond(seconds) : fallback;
    }
}
