package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokEscrowDetail;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementListResult;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementSummary;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokFinanceController Unit Tests")
class TiktokFinanceControllerTest {

    private TiktokTenantConnections tenantConnections;
    private TiktokFinanceClient financeClient;
    private TiktokFinanceController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        financeClient = mock(TiktokFinanceClient.class);
        controller = new TiktokFinanceController(tenantConnections, financeClient);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("getSettlements returns settlement statements list")
    void getSettlements_Success() {
        TiktokSettlementSummary stmt = new TiktokSettlementSummary(
                "STMT-001", 1700000000L, new BigDecimal("2500000"), "VND", "PAID", 1700086400L
        );
        TiktokSettlementListResult result = new TiktokSettlementListResult(List.of(stmt), "next-token-1", 1);

        when(financeClient.getSettlements(eq(77L), eq(1700000000L), eq(1700086400L), eq(20), isNull()))
                .thenReturn(result);

        ResponseEntity<ApiResponse<TiktokSettlementListResult>> res =
                controller.getSettlements(1700000000L, 1700086400L, 20, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(1, res.getBody().getData().settlements().size());
        assertEquals("STMT-001", res.getBody().getData().settlements().get(0).statementId());
        assertEquals(new BigDecimal("2500000"), res.getBody().getData().settlements().get(0).settlementAmount());
    }

    @Test
    @DisplayName("getEscrowDetail returns itemized financial escrow breakdown")
    void getEscrowDetail_Success() {
        TiktokEscrowDetail escrow = new TiktokEscrowDetail(
                "ORD-TK-001",
                new BigDecimal("450000"),  // escrowAmount
                new BigDecimal("500000"),  // buyerTotalAmount
                new BigDecimal("500000"),  // orderOriginalPrice
                BigDecimal.ZERO,           // platformDiscount
                BigDecimal.ZERO,           // sellerDiscount
                new BigDecimal("25000"),   // commissionFee
                new BigDecimal("10000"),   // serviceFee
                new BigDecimal("15000"),   // transactionFee
                BigDecimal.ZERO,           // shippingFee
                BigDecimal.ZERO,           // refundAmount
                "VND",
                "TikTokPay"
        );

        when(financeClient.getEscrowDetail(77L, "ORD-TK-001")).thenReturn(escrow);

        ResponseEntity<ApiResponse<TiktokEscrowDetail>> res = controller.getEscrowDetail("ORD-TK-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        TiktokEscrowDetail data = res.getBody().getData();
        assertEquals("ORD-TK-001", data.orderId());
        assertEquals(new BigDecimal("450000"), data.escrowAmount());
        assertEquals(new BigDecimal("25000"), data.commissionFee());
        assertEquals("TikTokPay", data.paymentMethod());
    }
}

