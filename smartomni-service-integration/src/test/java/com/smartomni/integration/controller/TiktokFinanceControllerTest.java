package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.SettlementTotals;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokEscrowDetail;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementListResult;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementSummary;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.exception.TiktokApiExceptionHandler;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokFinanceEstimateService;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TiktokFinanceController Unit Tests")
class TiktokFinanceControllerTest {

    private TiktokTenantConnections tenantConnections;
    private TiktokFinanceClient financeClient;
    private TiktokFinanceEstimateService estimateService;
    private TiktokFinanceController controller;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        financeClient = mock(TiktokFinanceClient.class);
        estimateService = mock(TiktokFinanceEstimateService.class);
        controller = new TiktokFinanceController(tenantConnections, financeClient, estimateService);

        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("settlements are an estimate from completed orders, flagged estimated with its source")
    void getSettlements_IsAnEstimate() {
        TiktokSettlementListResult result = new TiktokSettlementListResult(
                List.of(new TiktokSettlementSummary("ORD-1", 1800000000L, new BigDecimal("250000"), "VND", "ESTIMATED", null)),
                null, 1, true, TiktokFinanceClient.SOURCE_ESTIMATED,
                new SettlementTotals(1, new BigDecimal("230000"), BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("20000"), BigDecimal.ZERO, new BigDecimal("250000"), "VND"));
        when(estimateService.estimate(77L, 1700000000L, 1700086400L)).thenReturn(result);

        ResponseEntity<ApiResponse<TiktokSettlementListResult>> res =
                controller.getSettlements(1700000000L, 1700086400L, 20, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        TiktokSettlementListResult data = res.getBody().getData();
        assertTrue(data.estimated());
        assertEquals("ESTIMATED_FROM_ORDERS", data.source());
        assertEquals(new BigDecimal("250000"), data.totals().totalAmount());
        verifyNoInteractions(financeClient);
    }

    @Test
    @DisplayName("escrow comes from price_detail and carries its source")
    void getEscrowDetail_FromPriceDetail() throws Exception {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        ObjectMapper mapper = new ObjectMapper();
        when(executor.call(77L, "GET", "/order/202407/orders/ORD-1/price_detail", Map.of(), null))
                .thenReturn(mapper.readTree(TiktokFinanceClientFixtures.PRICE_DETAIL));
        controller = new TiktokFinanceController(tenantConnections, new TiktokFinanceClient(executor, mapper), estimateService);

        TiktokEscrowDetail data = controller.getEscrowDetail("ORD-1").getBody().getData();

        assertEquals("ORDER_PRICE_DETAIL", data.source());
        assertEquals(new BigDecimal("265000"), data.buyerTotalAmount());
        assertNull(data.escrowAmount());
        assertNull(data.commissionFee());
        assertEquals(2, data.lineItems().size());
    }

    @Test
    @DisplayName("unknown order answers an explicit error, not 500")
    void getEscrowDetail_UnknownOrderIsExplicit() throws Exception {
        when(financeClient.getEscrowDetail(77L, "NOPE"))
                .thenThrow(new TiktokApiException("TikTok rejected the request: HTTP 404 order does not exist", 404, null, null, false));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TiktokApiExceptionHandler(), new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/tiktok/finance/escrow/NOPE"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TIKTOK_NOT_FOUND"));
    }
}
