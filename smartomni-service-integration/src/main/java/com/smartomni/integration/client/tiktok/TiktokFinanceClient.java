package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Money of a TikTok order, built from order data because neither the simulator nor its contracts expose a finance
 * API (no {@code /finance/202309/statements}, no {@code statement_transactions}). The escrow view is read from
 * {@code GET /order/202407/orders/{order_id}/price_detail} (scope {@code sim.order.read}); settlements are estimated
 * by {@code TiktokFinanceEstimateService} from COMPLETED orders.
 */
@Slf4j
@Component
public class TiktokFinanceClient {

    public static final String SOURCE_PRICE_DETAIL = "ORDER_PRICE_DETAIL";
    public static final String SOURCE_ESTIMATED = "ESTIMATED_FROM_ORDERS";
    static final String PRICE_DETAIL_PATH = "/order/202407/orders/%s/price_detail";

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    public TiktokFinanceClient(TiktokShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    /** Price breakdown of the whole order or of one line item (amounts are VND). */
    public record PriceBreakdown(
            String id,
            BigDecimal skuListPrice,
            BigDecimal skuSalePrice,
            BigDecimal subtotalDeductionSeller,
            BigDecimal subtotalDeductionPlatform,
            BigDecimal subtotal,
            BigDecimal shippingListPrice,
            BigDecimal shippingFeeDeductionSeller,
            BigDecimal shippingFeeDeductionPlatform,
            BigDecimal shippingSalePrice,
            BigDecimal taxAmount,
            BigDecimal payment,
            BigDecimal total
    ) {}

    /**
     * Escrow view of an order. Fields that only a finance statement could provide (escrow/settlement amount,
     * commission, service and transaction fees, refunds) are {@code null}: unknown, not zero.
     * {@code buyerTotalAmount} is {@code payment} (what the buyer pays), {@code orderOriginalPrice} is
     * {@code sku_list_price}, {@code shippingFee} is {@code shipping_sale_price}.
     */
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
            String paymentMethod,
            PriceBreakdown order,
            List<PriceBreakdown> lineItems,
            String source
    ) {}

    public record TiktokSettlementSummary(
            String statementId,
            Long statementTime,
            BigDecimal settlementAmount,
            String currency,
            String status,
            Long paidTime
    ) {}

    /** Sums of the {@code payment} block of the orders counted in an estimate. */
    public record SettlementTotals(
            int orderCount,
            BigDecimal subTotal,
            BigDecimal sellerDiscount,
            BigDecimal platformDiscount,
            BigDecimal shippingFee,
            BigDecimal tax,
            BigDecimal totalAmount,
            String currency
    ) {}

    /**
     * Settlements result. With {@code estimated=true} and {@code source=ESTIMATED_FROM_ORDERS} each row is one
     * COMPLETED order ({@code statementId} = order id, {@code settlementAmount} = {@code payment.total_amount}) and
     * {@code totals} holds the sums: this is NOT a platform statement.
     */
    public record TiktokSettlementListResult(
            List<TiktokSettlementSummary> settlements,
            String nextPageToken,
            int total,
            boolean estimated,
            String source,
            SettlementTotals totals
    ) {
        public TiktokSettlementListResult(List<TiktokSettlementSummary> settlements, String nextPageToken, int total) {
            this(settlements, nextPageToken, total, false, null, null);
        }
    }

    /**
     * Escrow view of one order from Get Price Detail ({@code GET /order/202407/orders/{order_id}/price_detail}).
     *
     * @throws BusinessException 400 {@code TIKTOK_ORDER_ID_INVALID} for a malformed id
     * @throws TiktokApiException when TikTok fails; an unknown order is TikTok's 404
     */
    public TiktokEscrowDetail getEscrowDetail(Long connectionId, String orderId) {
        if (orderId == null || !orderId.trim().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException("Invalid TikTok order id", HttpStatus.BAD_REQUEST, "TIKTOK_ORDER_ID_INVALID");
        }
        String id = orderId.trim();
        JsonNode data = executor.call(connectionId, "GET", String.format(PRICE_DETAIL_PATH, id), Map.of(), null);

        PriceBreakdown order = breakdown(id, data);
        List<PriceBreakdown> lines = new ArrayList<>();
        for (JsonNode line : data.path("line_items")) {
            lines.add(breakdown(line.path("id").asText(null), line));
        }
        return new TiktokEscrowDetail(
                id,
                null,
                order.payment(),
                order.skuListPrice(),
                order.subtotalDeductionPlatform(),
                order.subtotalDeductionSeller(),
                null,
                null,
                null,
                order.shippingSalePrice(),
                null,
                data.path("currency").asText("VND"),
                null,
                order,
                lines,
                SOURCE_PRICE_DETAIL);
    }

    private static PriceBreakdown breakdown(String id, JsonNode node) {
        return new PriceBreakdown(id,
                amount(node, "sku_list_price"),
                amount(node, "sku_sale_price"),
                amount(node, "subtotal_deduction_seller"),
                amount(node, "subtotal_deduction_platform"),
                amount(node, "subtotal"),
                amount(node, "shipping_list_price"),
                amount(node, "shipping_fee_deduction_seller"),
                amount(node, "shipping_fee_deduction_platform"),
                amount(node, "shipping_sale_price"),
                amount(node, "tax_amount"),
                amount(node, "payment"),
                amount(node, "total"));
    }

    /** TikTok amounts are strings; null when absent or not numeric. */
    static BigDecimal amount(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
