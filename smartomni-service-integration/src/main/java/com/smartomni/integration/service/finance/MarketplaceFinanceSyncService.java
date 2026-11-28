package com.smartomni.integration.service.finance;

import com.smartomni.common.dto.OrderFinanceSnapshotRequest;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowAnalyticsDetail;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.OrderStatementTransaction;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Fetches normalized marketplace finance data (Shopee escrow detail & TikTok statement_transactions)
 * and persists per-order snapshots in order-service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceFinanceSyncService {

    private final ShopeeFinanceClient shopeeFinanceClient;
    private final TiktokFinanceClient tiktokFinanceClient;
    private final OrderServiceClient orderServiceClient;

    public void syncShopeeOrderFinance(MarketplaceConnection connection, String orderSn, String orderStatus) {
        if (connection == null || orderSn == null || orderSn.isBlank()) {
            return;
        }
        try {
            EscrowAnalyticsDetail d = shopeeFinanceClient.getEscrowAnalyticsDetail(connection.getId(), orderSn);
            BigDecimal commission = nz(d.commissionFee());
            BigDecimal service = nz(d.serviceFee());
            BigDecimal transaction = nz(d.sellerTransactionFee());
            BigDecimal tax = nz(d.escrowTax());
            BigDecimal platformFees = commission.add(service).add(transaction);

            BigDecimal actualShip = nz(d.actualShippingFee());
            BigDecimal buyerShip = nz(d.buyerPaidShippingFee());
            BigDecimal rebate = nz(d.shopeeShippingRebate());
            BigDecimal sellerShipDisc = nz(d.sellerShippingDiscount());
            BigDecimal sellerShippingCost = actualShip.subtract(buyerShip).subtract(rebate).subtract(sellerShipDisc).max(BigDecimal.ZERO);

            BigDecimal sellerReturnRefund = d.sellerReturnRefund() != null ? d.sellerReturnRefund().abs() : BigDecimal.ZERO;
            BigDecimal reverseShipping = nz(d.reverseShippingFee());
            BigDecimal merch = d.orderDiscountedPrice() != null ? d.orderDiscountedPrice()
                    : (d.orderOriginalPrice() != null ? d.orderOriginalPrice().subtract(nz(d.voucherFromSeller())).max(BigDecimal.ZERO) : null);

            boolean released = "COMPLETED".equalsIgnoreCase(orderStatus);
            OrderFinanceSnapshotRequest req = OrderFinanceSnapshotRequest.builder()
                    .tenantId(connection.getTenantId())
                    .connectionId(connection.getId())
                    .platform("SHOPEE")
                    .platformOrderId(orderSn)
                    .currency("VND")
                    .settlementStatus(released ? "RELEASED" : "PENDING")
                    .estimated(!released)
                    .buyerPayment(d.buyerTotalAmount())
                    .merchandiseValue(merch)
                    .commissionFee(d.commissionFee())
                    .serviceFee(d.serviceFee())
                    .transactionFee(d.sellerTransactionFee())
                    .taxAmount(d.escrowTax())
                    .platformFees(platformFees)
                    .actualShippingFee(d.actualShippingFee())
                    .buyerPaidShippingFee(d.buyerPaidShippingFee())
                    .platformShippingRebate(d.shopeeShippingRebate())
                    .sellerShippingDiscount(d.sellerShippingDiscount())
                    .sellerShippingCost(sellerShippingCost)
                    .sellerReturnRefund(sellerReturnRefund)
                    .reverseShippingFee(reverseShipping)
                    .otherAdjustments(tax)
                    .sellerSettlement(d.escrowAmount())
                    .sourceEndpoint("GET /api/v2/payment/get_escrow_detail")
                    .sourceWatermark(Instant.now())
                    .build();
            orderServiceClient.upsertFinanceSnapshot(req);
        } catch (Exception ex) {
            log.warn("Best-effort Shopee finance snapshot sync skipped for order {}: {}", orderSn, ex.getMessage());
        }
    }

    public void syncTiktokOrderFinance(MarketplaceConnection connection, String orderId) {
        if (connection == null || orderId == null || orderId.isBlank()) {
            return;
        }
        try {
            OrderStatementTransaction txn = tiktokFinanceClient.getOrderStatementTransaction(connection.getId(), orderId);
            BigDecimal commission = absNz(txn.platformCommissionAmount());
            BigDecimal service = absNz(txn.serviceFeeAmount());
            BigDecimal transaction = absNz(txn.transactionFeeAmount());
            BigDecimal tax = absNz(txn.taxAmount());
            BigDecimal adj = absNz(txn.adjustmentAmount());
            BigDecimal platformFees = commission.add(service).add(transaction);

            BigDecimal actualShip = absNz(txn.actualShippingFeeAmount());
            BigDecimal customerShip = nz(txn.customerShippingFeeAmount());
            BigDecimal subsidy = nz(txn.shippingFeeSubsidyAmount());
            BigDecimal sellerShippingCost = actualShip.subtract(customerShip).subtract(subsidy).max(BigDecimal.ZERO);

            BigDecimal sellerReturnRefund = absNz(txn.customerRefundAmount());
            BigDecimal reverseShipping = absNz(txn.returnShippingFeeAmount());
            BigDecimal gross = nz(txn.grossSalesAmount());
            BigDecimal sellerDisc = absNz(txn.sellerDiscountAmount());
            BigDecimal merch = gross.subtract(sellerDisc).max(BigDecimal.ZERO);

            OrderFinanceSnapshotRequest req = OrderFinanceSnapshotRequest.builder()
                    .tenantId(connection.getTenantId())
                    .connectionId(connection.getId())
                    .platform("TIKTOK_SHOP")
                    .platformOrderId(orderId)
                    .currency(txn.currency() != null ? txn.currency() : "VND")
                    .settlementStatus(txn.status() != null ? txn.status() : "UNSETTLED")
                    .estimated(txn.estimated())
                    .statementId(txn.statementId())
                    .statementTime(txn.statementTime() != null ? Instant.ofEpochSecond(txn.statementTime()) : null)
                    .merchandiseValue(merch)
                    .commissionFee(commission)
                    .serviceFee(service)
                    .transactionFee(transaction)
                    .taxAmount(tax)
                    .platformFees(platformFees)
                    .actualShippingFee(actualShip)
                    .buyerPaidShippingFee(customerShip)
                    .platformShippingRebate(subsidy)
                    .sellerShippingDiscount(absNz(txn.sellerShippingDiscountAmount()))
                    .sellerShippingCost(sellerShippingCost)
                    .sellerReturnRefund(sellerReturnRefund)
                    .reverseShippingFee(reverseShipping)
                    .otherAdjustments(tax.add(adj))
                    .sellerSettlement(txn.settlementAmount())
                    .sourceEndpoint("GET /finance/202501/orders/{order_id}/statement_transactions")
                    .sourceWatermark(Instant.now())
                    .build();
            orderServiceClient.upsertFinanceSnapshot(req);
        } catch (Exception ex) {
            log.warn("Best-effort TikTok finance snapshot sync skipped for order {}: {}", orderId, ex.getMessage());
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal absNz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.abs();
    }
}
