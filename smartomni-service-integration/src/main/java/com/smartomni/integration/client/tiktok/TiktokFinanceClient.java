package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Client for TikTok Shop Payment, Escrow, and Financial Ledger APIs (UC-TK-05).
 */
@Slf4j
@Component
public class TiktokFinanceClient {

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    public TiktokFinanceClient(TiktokShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public record TiktokEscrowDetail(
            String orderId,
            BigDecimal escrowAmount,
            BigDecimal buyerTotalAmount,
            BigDecimal orderOriginalPrice,
            BigDecimal platformDiscount,
            BigDecimal sellerDiscount,
            BigDecimal commissionFee,
            BigDecimal serviceFee,
            BigDecimal transactionFee,
            BigDecimal shippingFee,
            BigDecimal refundAmount,
            String currency,
            String paymentMethod
    ) {}

    public record TiktokSettlementSummary(
            String statementId,
            Long statementTime,
            BigDecimal settlementAmount,
            String currency,
            String status,
            Long paidTime
    ) {}

    public record TiktokSettlementListResult(
            List<TiktokSettlementSummary> settlements,
            String nextPageToken,
            int total
    ) {}

    /**
     * Retrieves financial escrow breakdown for an order (GET /finance/202309/orders/{order_id}/statement_transactions).
     */
    public TiktokEscrowDetail getEscrowDetail(Long connectionId, String orderId) {
        String path = "/finance/202309/orders/" + orderId + "/statement_transactions";
        JsonNode data = executor.call(connectionId, "GET", path, Map.of(), null);

        JsonNode income = data.path("statement_transaction");
        if (income.isMissingNode() || income.isNull()) {
            income = data;
        }

        BigDecimal rawEscrow = BigDecimal.valueOf(income.path("net_settlement_amount").asDouble(
                income.path("escrow_amount").asDouble(0.0)));
        // Safe display guarantee: clamped >= 0 VND for merchant settlement display
        BigDecimal escrowAmount = rawEscrow.max(BigDecimal.ZERO);

        BigDecimal buyerTotal = BigDecimal.valueOf(income.path("buyer_paid_amount").asDouble(
                income.path("buyer_total_amount").asDouble(0.0)));
        BigDecimal originalPrice = BigDecimal.valueOf(income.path("order_original_price").asDouble(0.0));
        BigDecimal platformDiscount = BigDecimal.valueOf(income.path("platform_discount").asDouble(0.0));
        BigDecimal sellerDiscount = BigDecimal.valueOf(income.path("seller_discount").asDouble(0.0));
        BigDecimal commission = BigDecimal.valueOf(income.path("commission_fee").asDouble(
                income.path("platform_commission").asDouble(0.0)));
        BigDecimal service = BigDecimal.valueOf(income.path("service_fee").asDouble(0.0));
        BigDecimal transaction = BigDecimal.valueOf(income.path("transaction_fee").asDouble(
                income.path("seller_transaction_fee").asDouble(0.0)));
        BigDecimal shippingFee = BigDecimal.valueOf(income.path("shipping_fee").asDouble(0.0));
        BigDecimal refundAmount = BigDecimal.valueOf(income.path("refund_amount").asDouble(0.0));
        String currency = income.path("currency").asText("VND");
        String paymentMethod = income.path("payment_method").asText("TikTokPay");

        return new TiktokEscrowDetail(
                orderId,
                escrowAmount,
                buyerTotal,
                originalPrice,
                platformDiscount,
                sellerDiscount,
                commission,
                service,
                transaction,
                shippingFee,
                refundAmount,
                currency,
                paymentMethod
        );
    }

    /**
     * Retrieves paginated financial statements / settlements (GET /finance/202309/statements).
     */
    public TiktokSettlementListResult getSettlements(Long connectionId, Long fromDate, Long toDate,
                                                    Integer pageSize, String pageToken) {
        Map<String, String> query = new LinkedHashMap<>();
        if (fromDate != null) {
            query.put("create_time_from", String.valueOf(fromDate));
        }
        if (toDate != null) {
            query.put("create_time_to", String.valueOf(toDate));
        }
        query.put("page_size", String.valueOf(pageSize != null && pageSize > 0 ? Math.min(pageSize, 50) : 20));
        if (pageToken != null && !pageToken.isBlank()) {
            query.put("page_token", pageToken);
        }

        JsonNode data = executor.call(connectionId, "GET", "/finance/202309/statements", query, null);

        List<TiktokSettlementSummary> list = new ArrayList<>();
        JsonNode arr = data.path("statements");
        if (arr.isMissingNode() || arr.isNull() || !arr.isArray()) {
            arr = data.path("statement_list");
        }

        if (arr.isArray()) {
            for (JsonNode s : arr) {
                list.add(new TiktokSettlementSummary(
                        s.path("statement_id").asText(""),
                        s.hasNonNull("statement_time") ? s.path("statement_time").asLong() : null,
                        BigDecimal.valueOf(s.path("settlement_amount").asDouble(0.0)),
                        s.path("currency").asText("VND"),
                        s.path("status").asText("PAID"),
                        s.hasNonNull("paid_time") ? s.path("paid_time").asLong() : null
                ));
            }
        }

        String nextPageToken = data.path("next_page_token").asText(null);
        int total = data.path("total_count").asInt(list.size());

        return new TiktokSettlementListResult(list, nextPageToken, total);
    }
}

