package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokCancellationClient;
import com.smartomni.integration.controller.TiktokCancellationController.TiktokCancelOrderRequest;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokCancellationController Unit Tests")
class TiktokCancellationControllerTest {

    private TiktokTenantConnections tenantConnections;
    private TiktokCancellationClient cancellationClient;
    private TiktokCancellationController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        cancellationClient = mock(TiktokCancellationClient.class);
        controller = new TiktokCancellationController(tenantConnections, cancellationClient);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("cancelOrder submits active order cancellation and returns success map")
    void cancelOrder_Success() {
        when(cancellationClient.cancelOrder(77L, "ORD-TK-001", "OUT_OF_STOCK")).thenReturn(true);

        TiktokCancelOrderRequest request = TiktokCancelOrderRequest.builder()
                .shopId("SHOP-001")
                .orderId("ORD-TK-001")
                .cancelReason("OUT_OF_STOCK")
                .build();

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.cancelOrder(request);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Order cancellation submitted", res.getBody().getMessage());
        assertTrue((Boolean) res.getBody().getData().get("canceled"));
        assertEquals("ORD-TK-001", res.getBody().getData().get("orderId"));
    }

    @Test
    @DisplayName("approveCancellation approves buyer cancellation and returns success map")
    void approveCancellation_Success() {
        when(cancellationClient.approveCancellation(77L, "CANCEL-001")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.approveCancellation("CANCEL-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Cancellation approved successfully", res.getBody().getMessage());
        assertTrue((Boolean) res.getBody().getData().get("approved"));
        assertEquals("CANCEL-001", res.getBody().getData().get("cancelId"));
    }

    @Test
    @DisplayName("rejectCancellation rejects buyer cancellation with provided reason")
    void rejectCancellation_WithReason_Success() {
        when(cancellationClient.rejectCancellation(77L, "CANCEL-001", "PACKAGE_ALREADY_SHIPPED")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.rejectCancellation(
                "CANCEL-001", Map.of("rejectReason", "PACKAGE_ALREADY_SHIPPED"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Cancellation rejected successfully", res.getBody().getMessage());
        assertTrue((Boolean) res.getBody().getData().get("rejected"));
        assertEquals("CANCEL-001", res.getBody().getData().get("cancelId"));
    }

    @Test
    @DisplayName("rejectCancellation uses default reason when body is null")
    void rejectCancellation_NullBody_Success() {
        when(cancellationClient.rejectCancellation(77L, "CANCEL-001", "REJECTED_BY_SELLER")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.rejectCancellation("CANCEL-001", null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("rejected"));
        verify(cancellationClient).rejectCancellation(77L, "CANCEL-001", "REJECTED_BY_SELLER");
    }
}

