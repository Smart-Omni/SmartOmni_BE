package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.dto.tiktok.CreateTiktokVoucherRequest;
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
 * Client calling TikTok Simulator's Promotion Voucher API ({@code /api/promotion/vouchers}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokVoucherClient {

    public static final String VOUCHERS_PATH = "/api/promotion/vouchers";

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    /**
     * Issues a voucher on TikTok Simulator ({@code POST /api/promotion/vouchers}).
     */
    public TiktokVoucherDto createVoucher(Long connectionId, CreateTiktokVoucherRequest request) {
        ObjectNode body = mapper.createObjectNode();
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            body.put("title", request.getTitle().trim());
        }

        String discountType = request.getDiscountType() != null
                ? request.getDiscountType().trim().toUpperCase()
                : "AMOUNT_OFF";
        if ("PERCENT_OFF".equals(discountType)) {
            body.put("discount_percent", request.getDiscountValue().intValue());
            if (request.getMaxDiscountAmount() != null) {
                body.put("max_discount_amount", request.getMaxDiscountAmount().intValue());
            }
        } else {
            body.put("discount_amount", request.getDiscountValue().intValue());
        }

        if (request.getMinOrderAmount() != null && request.getMinOrderAmount().signum() > 0) {
            body.put("min_spend_amount", request.getMinOrderAmount().intValue());
        }
        if (request.getUsageLimit() != null) {
            body.put("usage_limit", request.getUsageLimit());
        }
        if (request.getCode() != null && !request.getCode().isBlank()) {
            body.put("code", request.getCode().trim().toUpperCase());
        }
        if (request.getCustomerId() != null && !request.getCustomerId().isBlank()) {
            body.put("customer_id", request.getCustomerId().trim());
        }
        if (request.getStartTime() != null) {
            body.put("starts_at", request.getStartTime().toString());
        }
        if (request.getEndTime() != null) {
            body.put("ends_at", request.getEndTime().toString());
        }
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            body.put("idempotency_key", request.getIdempotencyKey().trim());
        }

        JsonNode data = executor.call(connectionId, "POST", VOUCHERS_PATH, Map.of(), body.toString());
        return parseVoucher(data, request);
    }

    /**
     * Lists vouchers for a shop from TikTok Simulator ({@code GET /api/promotion/vouchers}).
     */
    public List<TiktokVoucherDto> listVouchers(Long connectionId) {
        JsonNode data = executor.call(connectionId, "GET", VOUCHERS_PATH, Map.of(), null);
        List<TiktokVoucherDto> list = new ArrayList<>();
        if (data != null && data.has("vouchers") && data.get("vouchers").isArray()) {
            for (JsonNode node : data.get("vouchers")) {
                list.add(parseVoucher(node, null));
            }
        }
        return list;
    }

    private TiktokVoucherDto parseVoucher(JsonNode data, CreateTiktokVoucherRequest fallback) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return null;
        }
        String voucherId = text(data, "voucher_id", text(data, "external_voucher_id", null));
        String code = text(data, "voucher_code", text(data, "code", fallback != null ? fallback.getCode() : null));
        String title = text(data, "title", fallback != null ? fallback.getTitle() : null);
        String status = text(data, "status", "ONGOING");
        String discountType = text(data, "discount_type",
                fallback != null && fallback.getDiscountType() != null ? fallback.getDiscountType().toUpperCase() : "AMOUNT_OFF");

        BigDecimal discountValue = null;
        if ("PERCENT_OFF".equalsIgnoreCase(discountType) && data.hasNonNull("discount_percent")) {
            discountValue = data.get("discount_percent").decimalValue();
        } else if (data.hasNonNull("discount_amount")) {
            discountValue = data.get("discount_amount").decimalValue();
        } else if (fallback != null) {
            discountValue = fallback.getDiscountValue();
        }

        BigDecimal maxDiscount = data.hasNonNull("max_discount_amount")
                ? data.get("max_discount_amount").decimalValue()
                : (fallback != null ? fallback.getMaxDiscountAmount() : null);
        BigDecimal minOrder = data.hasNonNull("min_spend_amount")
                ? data.get("min_spend_amount").decimalValue()
                : (fallback != null ? fallback.getMinOrderAmount() : null);
        Integer usageLimit = data.hasNonNull("usage_limit")
                ? data.get("usage_limit").asInt()
                : (fallback != null ? fallback.getUsageLimit() : null);
        Integer redeemedCount = data.hasNonNull("redeemed_count")
                ? data.get("redeemed_count").asInt()
                : 0;

        Instant startsAt = parseInstant(text(data, "starts_at", null), fallback != null ? fallback.getStartTime() : null);
        Instant endsAt = parseInstant(text(data, "ends_at", null), fallback != null ? fallback.getEndTime() : null);

        return TiktokVoucherDto.builder()
                .tiktokVoucherId(voucherId)
                .voucherCode(code)
                .title(title)
                .discountType(discountType)
                .discountValue(discountValue)
                .maxDiscountAmount(maxDiscount)
                .minOrderAmount(minOrder)
                .usageLimit(usageLimit)
                .redeemedCount(redeemedCount)
                .startTime(startsAt)
                .endTime(endsAt)
                .status(status)
                .build();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private static Instant parseInstant(String iso, Instant fallback) {
        if (iso == null || iso.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return fallback;
        }
    }
}
