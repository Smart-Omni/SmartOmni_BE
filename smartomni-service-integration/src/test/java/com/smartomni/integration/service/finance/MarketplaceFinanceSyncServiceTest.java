package com.smartomni.integration.service.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.OrderFinanceSnapshotRequest;
import com.smartomni.integration.client.order.OrderServiceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowAnalyticsDetail;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketplaceFinanceSyncServiceTest {

    @Test
    void syncTiktokOrderFinanceMapsStatementTransactionToSnapshot() throws Exception {
        ShopeeFinanceClient shopeeClient = mock(ShopeeFinanceClient.class);
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        ObjectMapper mapper = new ObjectMapper();
        when(executor.call(22L, "GET", "/finance/202501/orders/TT-100/statement_transactions", Map.of(), null))
                .thenReturn(mapper.readTree("""
                        {
                          "order_id": "TT-100",
                          "statement_transactions": [{
                            "order_id": "TT-100",
                            "transaction_id": "TXN-TT-100",
                            "statement_id": "ST-20261127",
                            "statement_time": 1795737600,
                            "status": "SETTLED",
                            "estimated": false,
                            "currency": "VND",
                            "revenue_amount": "200000",
                            "fee_and_tax_amount": "-18625",
                            "shipping_cost_amount": "-25000",
                            "adjustment_amount": "0",
                            "settlement_amount": "156375",
                            "revenue_breakdown": {
                              "gross_sales_amount": "200000",
                              "seller_discount_amount": "0",
                              "platform_discount_amount": "0",
                              "customer_refund_amount": "0"
                            },
                            "fee_tax_breakdown": {
                              "platform_commission_amount": "-6000",
                              "service_fee_amount": "-4000",
                              "transaction_fee_amount": "-5625",
                              "tax_amount": "-3000"
                            },
                            "shipping_cost_breakdown": {
                              "customer_shipping_fee_amount": "25000",
                              "actual_shipping_fee_amount": "-30000",
                              "shipping_fee_subsidy_amount": "0",
                              "seller_shipping_discount_amount": "0",
                              "return_shipping_fee_amount": "-20000"
                            }
                          }]
                        }
                        """));
        OrderServiceClient orderClient = mock(OrderServiceClient.class);
        MarketplaceFinanceSyncService syncService = new MarketplaceFinanceSyncService(
                shopeeClient, new TiktokFinanceClient(executor, mapper), orderClient);

        MarketplaceConnection conn = new MarketplaceConnection();
        conn.setId(22L);
        conn.setTenantId(5L);

        syncService.syncTiktokOrderFinance(conn, "TT-100");

        ArgumentCaptor<OrderFinanceSnapshotRequest> cap = ArgumentCaptor.forClass(OrderFinanceSnapshotRequest.class);
        verify(orderClient).upsertFinanceSnapshot(cap.capture());
        OrderFinanceSnapshotRequest req = cap.getValue();
        assertEquals(5L, req.getTenantId());
        assertEquals(22L, req.getConnectionId());
        assertEquals("TIKTOK_SHOP", req.getPlatform());
        assertEquals("SETTLED", req.getSettlementStatus());
        assertEquals(new BigDecimal("15625"), req.getPlatformFees());
        assertEquals(new BigDecimal("3000"), req.getOtherAdjustments());
        assertEquals(new BigDecimal("5000"), req.getSellerShippingCost());
        assertEquals(new BigDecimal("20000"), req.getReverseShippingFee());
        assertEquals(new BigDecimal("156375"), req.getSellerSettlement());
    }

    @Test
    void syncShopeeOrderFinanceMapsEscrowAnalyticsToSnapshot() {
        ShopeeFinanceClient shopeeClient = mock(ShopeeFinanceClient.class);
        TiktokFinanceClient tiktokClient = mock(TiktokFinanceClient.class);
        OrderServiceClient orderClient = mock(OrderServiceClient.class);
        when(shopeeClient.getEscrowAnalyticsDetail(11L, "SP-100")).thenReturn(new EscrowAnalyticsDetail(
                "SP-100",
                new BigDecimal("215000"),
                new BigDecimal("265000"),
                new BigDecimal("250000"),
                new BigDecimal("240000"),
                new BigDecimal("10000"),
                new BigDecimal("4800"),
                new BigDecimal("3600"),
                new BigDecimal("6625"),
                BigDecimal.ZERO,
                new BigDecimal("30000"),
                new BigDecimal("25000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("-5000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        MarketplaceFinanceSyncService syncService = new MarketplaceFinanceSyncService(shopeeClient, tiktokClient, orderClient);
        MarketplaceConnection conn = new MarketplaceConnection();
        conn.setId(11L);
        conn.setTenantId(5L);

        syncService.syncShopeeOrderFinance(conn, "SP-100", "COMPLETED");

        ArgumentCaptor<OrderFinanceSnapshotRequest> cap = ArgumentCaptor.forClass(OrderFinanceSnapshotRequest.class);
        verify(orderClient).upsertFinanceSnapshot(cap.capture());
        OrderFinanceSnapshotRequest req = cap.getValue();
        assertEquals("SHOPEE", req.getPlatform());
        assertEquals("RELEASED", req.getSettlementStatus());
        assertEquals(new BigDecimal("15025"), req.getPlatformFees());
        assertEquals(new BigDecimal("5000"), req.getSellerShippingCost());
        assertEquals(new BigDecimal("215000"), req.getSellerSettlement());
    }
}
