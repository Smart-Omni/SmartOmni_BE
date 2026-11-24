package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.SettlementTotals;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementListResult;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementSummary;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settlement ESTIMATE for a date range, summed by SmartOmni from the shop's COMPLETED orders. TikTok (and the
 * simulator) expose no statement API, so this is not a platform statement: fees and payouts are unknown.
 *
 * <p>Orders are searched with {@code order_status=COMPLETED} and {@code update_time} in {@code [fromDate, toDate)}
 * (epoch seconds), all pages; non-COMPLETED rows are ignored defensively. The sums are over the {@code payment}
 * block: {@code sub_total}, {@code seller_discount}, {@code platform_discount}, {@code shipping_fee}, {@code tax},
 * {@code total_amount}.
 */
@Service
@RequiredArgsConstructor
public class TiktokFinanceEstimateService {

    static final int PAGE_SIZE = 50;
    static final int MAX_PAGES = 200;
    static final long DEFAULT_RANGE_SECONDS = 30L * 24 * 3600;
    static final String COMPLETED = "COMPLETED";

    private final TiktokOrderClient orderClient;
    private final Clock clock = Clock.systemUTC();

    public TiktokSettlementListResult estimate(Long connectionId, Long fromDate, Long toDate) {
        long to = toDate != null ? toDate : clock.instant().getEpochSecond();
        long from = fromDate != null ? fromDate : to - DEFAULT_RANGE_SECONDS;
        if (from < 0 || from >= to) {
            throw new BusinessException("fromDate must be before toDate (epoch seconds)", HttpStatus.BAD_REQUEST,
                    "INVALID_DATE_RANGE");
        }

        List<TiktokSettlementSummary> rows = new ArrayList<>();
        BigDecimal subTotal = BigDecimal.ZERO;
        BigDecimal sellerDiscount = BigDecimal.ZERO;
        BigDecimal platformDiscount = BigDecimal.ZERO;
        BigDecimal shippingFee = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        String currency = "VND";

        Set<String> seenTokens = new HashSet<>();
        Set<String> seenOrders = new HashSet<>();
        String token = null;
        for (int page = 0; ; page++) {
            if (page >= MAX_PAGES) {
                throw new TiktokApiException("TikTok order pagination exceeded " + MAX_PAGES + " pages", 200, null, null, false);
            }
            OrderSearchResponse response = orderClient.searchOrders(connectionId, OrderSearchRequest.builder()
                    .orderStatus(COMPLETED).updateTimeFrom(from).updateTimeTo(to).pageSize(PAGE_SIZE).pageToken(token).build());
            if (response != null && response.getOrders() != null) {
                for (TiktokOrderDetailResponse order : response.getOrders()) {
                    if (!COMPLETED.equals(order.getOrderStatus()) || !seenOrders.add(String.valueOf(order.getOrderId()))) {
                        continue;
                    }
                    TiktokOrderDetailResponse.Payment p = order.getPayment();
                    BigDecimal orderTotal = p == null ? null : p.getTotalAmount();
                    if (p != null) {
                        subTotal = add(subTotal, p.getSubTotal());
                        sellerDiscount = add(sellerDiscount, p.getSellerDiscount());
                        platformDiscount = add(platformDiscount, p.getPlatformDiscount());
                        shippingFee = add(shippingFee, p.getShippingFee());
                        tax = add(tax, p.getTax());
                        totalAmount = add(totalAmount, orderTotal);
                        if (p.getCurrency() != null) currency = p.getCurrency();
                    }
                    rows.add(new TiktokSettlementSummary(order.getOrderId(), order.getUpdateTime(),
                            orderTotal != null ? orderTotal : BigDecimal.ZERO, currency, "ESTIMATED", null));
                }
            }
            token = response == null || response.getNextPageToken() == null || response.getNextPageToken().isBlank()
                    ? null : response.getNextPageToken().trim();
            if (token == null) break;
            if (!seenTokens.add(token)) {
                throw new TiktokApiException("TikTok order pagination repeated a page token", 200, null, null, false);
            }
        }

        SettlementTotals totals = new SettlementTotals(rows.size(), subTotal, sellerDiscount, platformDiscount,
                shippingFee, tax, totalAmount, currency);
        return new TiktokSettlementListResult(rows, null, rows.size(), true, TiktokFinanceClient.SOURCE_ESTIMATED, totals);
    }

    private static BigDecimal add(BigDecimal sum, BigDecimal value) {
        return value == null ? sum : sum.add(value);
    }
}
