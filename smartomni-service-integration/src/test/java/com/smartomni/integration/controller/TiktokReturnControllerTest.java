package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokReturnService;
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

@DisplayName("TiktokReturnController Unit Tests")
class TiktokReturnControllerTest {

    private TiktokTenantConnections tenantConnections;
    private TiktokReturnService returnService;
    private TiktokReturnController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        returnService = mock(TiktokReturnService.class);
        controller = new TiktokReturnController(tenantConnections, returnService);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("listReturns returns paginated return requests")
    void listReturns_Success() {
        TiktokReturnResponse ret = TiktokReturnResponse.builder()
                .returnId("RET-TK-001")
                .orderId("ORD-TK-001")
                .returnStatus("RETURN_REQUESTED")
                .refundAmount(new BigDecimal("150000"))
                .refundCurrency("VND")
                .build();

        ReturnSearchResponse response = ReturnSearchResponse.builder()
                .returns(List.of(ret))
                .totalCount(1)
                .build();

        when(returnService.searchReturns(eq(77L), any(ReturnSearchRequest.class))).thenReturn(response);

        ResponseEntity<ApiResponse<ReturnSearchResponse>> res =
                controller.listReturns("RETURN_REQUESTED", "ORD-TK-001", 20, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(1, res.getBody().getData().getReturns().size());
        assertEquals("RET-TK-001", res.getBody().getData().getReturns().get(0).getReturnId());
    }

    @Test
    @DisplayName("getReturnDetail returns single return detail")
    void getReturnDetail_Success() {
        TiktokReturnResponse detail = TiktokReturnResponse.builder()
                .returnId("RET-TK-001")
                .orderId("ORD-TK-001")
                .returnStatus("RETURN_REQUESTED")
                .refundAmount(new BigDecimal("150000"))
                .build();

        when(returnService.getReturnDetail(77L, "RET-TK-001")).thenReturn(detail);

        ResponseEntity<ApiResponse<TiktokReturnResponse>> res = controller.getReturnDetail("RET-TK-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("RET-TK-001", res.getBody().getData().getReturnId());
    }

    @Test
    @DisplayName("approveReturn returns success map")
    void approveReturn_Success() {
        when(returnService.approveReturn(77L, "RET-TK-001")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.approveReturn("RET-TK-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("approved"));
        assertEquals("RET-TK-001", res.getBody().getData().get("returnId"));
    }

    @Test
    @DisplayName("rejectReturn returns success map with given reason")
    void rejectReturn_WithReason_Success() {
        when(returnService.rejectReturn(77L, "RET-TK-001", "DAMAGED_BY_BUYER")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.rejectReturn("RET-TK-001",
                Map.of("rejectReason", "DAMAGED_BY_BUYER"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("rejected"));
        assertEquals("RET-TK-001", res.getBody().getData().get("returnId"));
    }

    @Test
    @DisplayName("rejectReturn uses default reason when body is null")
    void rejectReturn_NullBody_Success() {
        when(returnService.rejectReturn(77L, "RET-TK-001", "REJECTED_BY_SELLER")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.rejectReturn("RET-TK-001", null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("rejected"));
        verify(returnService).rejectReturn(77L, "RET-TK-001", "REJECTED_BY_SELLER");
    }
}

