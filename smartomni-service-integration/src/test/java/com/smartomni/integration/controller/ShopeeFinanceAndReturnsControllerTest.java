package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowDetail;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnListResult;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ShopeeReturnDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.finance.ShopeeFinanceAndReturnsService;
import com.smartomni.integration.service.finance.ShopeeFinanceAndReturnsService.DisputeReturnRequest;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeFinanceAndReturnsController Unit Tests")
class ShopeeFinanceAndReturnsControllerTest {

    private ShopeeTenantConnections tenantConnections;
    private ShopeeFinanceAndReturnsService service;
    private ShopeeFinanceAndReturnsController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(ShopeeTenantConnections.class);
        service = mock(ShopeeFinanceAndReturnsService.class);
        controller = new ShopeeFinanceAndReturnsController(tenantConnections, service);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("getEscrowDetail returns financial breakdown")
    void getEscrowDetail_Success() {
        EscrowDetail detail = new EscrowDetail(
                "SP01", new BigDecimal("439500"), new BigDecimal("450000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "ShopeePay"
        );
        when(service.getEscrowDetail(connection, "SP01")).thenReturn(detail);

        ResponseEntity<ApiResponse<EscrowDetail>> res = controller.getEscrowDetail("SP01");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(new BigDecimal("439500"), res.getBody().getData().escrowAmount());
    }

    @Test
    @DisplayName("listReturns returns paginated return requests")
    void listReturns_Success() {
        ReturnListResult listResult = new ReturnListResult(List.of(), false, 0);
        when(service.listReturns(connection, 1, 20, null)).thenReturn(listResult);

        ResponseEntity<ApiResponse<ReturnListResult>> res = controller.listReturns(1, 20, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
    }

    @Test
    @DisplayName("getReturnDetail returns return detail")
    void getReturnDetail_Success() {
        ShopeeReturnDetail detail = new ShopeeReturnDetail(
                "RET-01", "SP01", "REQUESTED", BigDecimal.TEN, "VND", "Reason", "Text",
                "buyer", List.of(), List.of(), List.of(), BigDecimal.ZERO, ""
        );
        when(service.getReturnDetail(connection, "RET-01")).thenReturn(detail);

        ResponseEntity<ApiResponse<ShopeeReturnDetail>> res = controller.getReturnDetail("RET-01");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("RET-01", res.getBody().getData().returnSn());
    }

    @Test
    @DisplayName("confirmReturn returns success map")
    void confirmReturn_Success() {
        when(service.confirmReturn(connection, "RET-01")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.confirmReturn("RET-01");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("confirmed"));
    }

    @Test
    @DisplayName("disputeReturn returns success map")
    void disputeReturn_Success() {
        DisputeReturnRequest req = DisputeReturnRequest.builder()
                .disputeReasonId(1)
                .disputeTextReason("Text")
                .build();
        when(service.disputeReturn(eq(connection), eq("RET-01"), any())).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.disputeReturn("RET-01", req);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("disputed"));
    }
}
