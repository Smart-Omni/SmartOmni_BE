package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

/**
 * Client for Shopee Payment, Escrow, and Financial Ledger APIs (UC-12, Phase 6).
 */
@Component
public class ShopeeFinanceClient {

    private final ShopeeShopApiExecutor executor;
    private final ObjectMapper mapper;

    public ShopeeFinanceClient(ShopeeShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public record EscrowDetail(
            String orderSn,
            BigDecimal escrowAmount,
            BigDecimal buyerTotalAmount,
            BigDecimal orderOriginalPrice,
            BigDecimal shopeeShippingRebate,
            BigDecimal commissionFee,
            BigDecimal serviceFee,
            BigDecimal sellerTransactionFee,
            BigDecimal reverseShippingFee,
            BigDecimal sellerReturnRefund,
            String buyerPaymentMethod
    ) {}

    /**
     * Retrieves financial escrow breakdown for an order (GET /api/v2/payment/get_escrow_detail).
     */
    public EscrowDetail getEscrowDetail(Long connectionId, String orderSn) {
        Map<String, String> query = Map.of("order_sn", orderSn);
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/payment/get_escrow_detail", query, null);

        JsonNode income = data.path("order_income");
        if (income.isMissingNode() || income.isNull()) {
            income = data;
        }

        BigDecimal rawEscrow = BigDecimal.valueOf(income.path("escrow_amount").asDouble(0.0));
        // Safe display guarantee: clamped >= 0 VND for merchant settlement display
        BigDecimal escrowAmount = rawEscrow.max(BigDecimal.ZERO);

        BigDecimal buyerTotal = BigDecimal.valueOf(income.path("buyer_total_amount").asDouble(0.0));
        BigDecimal originalPrice = BigDecimal.valueOf(income.path("order_original_price").asDouble(0.0));
        BigDecimal shippingRebate = BigDecimal.valueOf(income.path("shopee_shipping_rebate").asDouble(0.0));
        BigDecimal commission = BigDecimal.valueOf(income.path("commission_fee").asDouble(0.0));
        BigDecimal service = BigDecimal.valueOf(income.path("service_fee").asDouble(0.0));
        BigDecimal transaction = BigDecimal.valueOf(income.path("seller_transaction_fee").asDouble(0.0));
        BigDecimal reverseShipping = BigDecimal.valueOf(income.path("reverse_shipping_fee").asDouble(0.0));
        BigDecimal returnRefund = BigDecimal.valueOf(income.path("seller_return_refund").asDouble(0.0));
        String paymentMethod = income.path("buyer_payment_method").asText("ShopeePay");

        return new EscrowDetail(
                orderSn,
                escrowAmount,
                buyerTotal,
                originalPrice,
                shippingRebate,
                commission,
                service,
                transaction,
                reverseShipping,
                returnRefund,
                paymentMethod
        );
    }

    /** Nullable analytics-facing escrow breakdown (preserves null when Shopee omits a numeric field). */
    public record EscrowAnalyticsDetail(
            String orderSn,
            BigDecimal escrowAmount,
            BigDecimal buyerTotalAmount,
            BigDecimal orderOriginalPrice,
            BigDecimal orderDiscountedPrice,
            BigDecimal voucherFromSeller,
            BigDecimal commissionFee,
            BigDecimal serviceFee,
            BigDecimal sellerTransactionFee,
            BigDecimal escrowTax,
            BigDecimal actualShippingFee,
            BigDecimal buyerPaidShippingFee,
            BigDecimal shopeeShippingRebate,
            BigDecimal sellerShippingDiscount,
            BigDecimal finalShippingFee,
            BigDecimal sellerReturnRefund,
            BigDecimal reverseShippingFee
    ) {}

    public EscrowAnalyticsDetail getEscrowAnalyticsDetail(Long connectionId, String orderSn) {
        Map<String, String> query = Map.of("order_sn", orderSn);
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/payment/get_escrow_detail", query, null);

        JsonNode income = data.path("order_income");
        if (income.isMissingNode() || income.isNull()) {
            income = data;
        }

        return new EscrowAnalyticsDetail(
                orderSn,
                nullableAmount(income, "escrow_amount"),
                nullableAmount(income, "buyer_total_amount"),
                nullableAmount(income, "order_original_price"),
                nullableAmount(income, "order_discounted_price"),
                nullableAmount(income, "voucher_from_seller"),
                nullableAmount(income, "commission_fee"),
                nullableAmount(income, "service_fee"),
                nullableAmount(income, "seller_transaction_fee"),
                nullableAmount(income, "escrow_tax"),
                nullableAmount(income, "actual_shipping_fee"),
                nullableAmount(income, "buyer_paid_shipping_fee"),
                nullableAmount(income, "shopee_shipping_rebate"),
                nullableAmount(income, "seller_shipping_discount"),
                nullableAmount(income, "final_shipping_fee"),
                nullableAmount(income, "seller_return_refund"),
                nullableAmount(income, "reverse_shipping_fee")
        );
    }

    private static BigDecimal nullableAmount(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            String text = value.asText("").trim();
            return text.isEmpty() ? null : new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
