package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Shopee Open API v2 voucher endpoints (add_voucher, get_voucher_list) used for loyalty rewards. */
@Component
public class ShopeeVoucherClient {

    /** Shopee reward_type: 2 = percentage discount (1 = fixed amount, 3 = coins). */
    static final int REWARD_PERCENTAGE = 2;
    /** Shopee voucher_type: 1 = shop voucher (2 = product voucher). */
    static final int SHOP_VOUCHER = 1;
    private static final int LIST_PAGE_SIZE = 100;
    private static final int MAX_LIST_PAGES = 20;

    private final ShopeeShopApiExecutor executor;
    private final ObjectMapper mapper;

    public ShopeeVoucherClient(ShopeeShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public record ShopeeVoucher(long voucherId, String code, String name) {
    }

    /** Creates a single-use shop-wide percentage voucher and returns its voucher_id. */
    public long addPercentVoucher(Long connectionId, String name, String code, BigDecimal percentage,
                                  long startTimeSec, long endTimeSec) {
        ObjectNode body = mapper.createObjectNode();
        body.put("voucher_name", name);
        body.put("voucher_code", code);
        body.put("start_time", startTimeSec);
        body.put("end_time", endTimeSec);
        body.put("voucher_type", SHOP_VOUCHER);
        body.put("reward_type", REWARD_PERCENTAGE);
        body.put("usage_quantity", 1);
        body.put("min_basket_price", 0);
        body.put("percentage", percentage.stripTrailingZeros().intValue());
        JsonNode data = executor.call(connectionId, "POST", "/api/v2/voucher/add_voucher", Map.of(), body.toString());
        return data.path("voucher_id").asLong();
    }

    /** The shop's voucher with this code (any status but deleted), if any. */
    public Optional<ShopeeVoucher> findByCode(Long connectionId, String code) {
        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("status", "all");
            query.put("page_no", String.valueOf(page));
            query.put("page_size", String.valueOf(LIST_PAGE_SIZE));
            JsonNode data = executor.call(connectionId, "GET", "/api/v2/voucher/get_voucher_list", query, null);
            for (JsonNode v : data.path("voucher_list")) {
                if (code.equalsIgnoreCase(v.path("voucher_code").asText(""))) {
                    return Optional.of(new ShopeeVoucher(v.path("voucher_id").asLong(), v.path("voucher_code").asText(),
                            v.path("voucher_name").asText("")));
                }
            }
            if (!data.path("more").asBoolean(false)) {
                break;
            }
        }
        return Optional.empty();
    }
}
