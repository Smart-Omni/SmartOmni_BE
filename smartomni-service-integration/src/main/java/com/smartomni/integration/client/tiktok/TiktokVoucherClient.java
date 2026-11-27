package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only TikTok coupon APIs: {@code POST /promotion/202406/coupons/search} and
 * {@code GET /promotion/202406/coupons/{couponId}}. Coupons cannot be created through the API (the simulator has
 * no such route and answers 404 to every write on /promotion): the seller creates a batch of single-use codes on
 * Seller Center, and SmartOmni pulls them by batch title.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokVoucherClient {

    public static final String SEARCH_COUPONS_PATH = "/promotion/202406/coupons/search";
    public static final String COUPON_DETAIL_PATH = "/promotion/202406/coupons/";
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 100;

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    /** Every coupon of the shop (all pages). */
    public List<TiktokVoucherDto> searchCoupons(Long connectionId) {
        return searchCoupons(connectionId, null, null);
    }

    /**
     * Search Coupons with {@code page_size=100}, following {@code next_page_token} to the end.
     *
     * @param titleKeyword batch title (case-insensitive "contains"); null for all
     * @param statuses     e.g. {@code ["ONGOING"]}; null for all
     */
    public List<TiktokVoucherDto> searchCoupons(Long connectionId, String titleKeyword, List<String> statuses) {
        ObjectNode body = mapper.createObjectNode();
        if (titleKeyword != null && !titleKeyword.isBlank()) {
            body.put("title_keyword", titleKeyword.trim());
        }
        if (statuses != null && !statuses.isEmpty()) {
            ArrayNode status = body.putArray("status");
            statuses.forEach(status::add);
        }
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize TikTok coupon search", e);
        }

        List<TiktokVoucherDto> list = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page_size", Integer.toString(PAGE_SIZE));
            if (token != null) {
                query.put("page_token", token);
            }
            JsonNode data = executor.call(connectionId, "POST", SEARCH_COUPONS_PATH, query, json);
            if (data != null && data.path("coupons").isArray()) {
                for (JsonNode node : data.get("coupons")) {
                    TiktokVoucherDto parsed = parseCoupon(node);
                    if (parsed != null) {
                        list.add(parsed);
                    }
                }
            }
            token = data == null ? null : data.path("next_page_token").asText("");
            if (token == null || token.isBlank()) {
                return list;
            }
            if (!seenTokens.add(token)) {
                throw new TiktokApiException("TikTok coupon pagination repeated a page token", 200, null, null, false);
            }
        }
        throw new TiktokApiException("TikTok coupon pagination exceeded " + MAX_PAGES + " pages", 200, null, null, false);
    }

    /** One coupon with {@code usage_stats} ({@code GET /promotion/202406/coupons/{couponId}}). */
    public TiktokVoucherDto getCoupon(Long connectionId, String couponId) {
        JsonNode data = executor.call(connectionId, "GET", COUPON_DETAIL_PATH + couponId, Map.of(), null);
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
        // Search Coupon List carries no usage_stats: unknown stays null so a sync never resets a known count to 0.
        Integer redeemedCount = node.path("usage_stats").hasNonNull("redeemed_count")
                ? node.path("usage_stats").get("redeemed_count").asInt()
                : null;

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
