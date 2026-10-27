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
}
