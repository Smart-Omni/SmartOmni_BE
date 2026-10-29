package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.shopee.ShopeeOrderClient;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderListResult;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderSummary;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.ShopeeOrderDetail;
import com.smartomni.integration.dto.order.ShopeeOrderPayload;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.order.ShopeeOrderSyncService;
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

@DisplayName("ShopeeOrderController Unit Tests")
class ShopeeOrderControllerTest {

    private ShopeeTenantConnections tenantConnections;
    private ShopeeOrderClient orderClient;
    private ShopeeOrderSyncService orderSyncService;
    private ShopeeOrderController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(ShopeeTenantConnections.class);
        orderClient = mock(ShopeeOrderClient.class);
        orderSyncService = mock(ShopeeOrderSyncService.class);

        controller = new ShopeeOrderController(tenantConnections, orderClient, orderSyncService);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("listOrders returns list of ShopeeOrderDetail")
    void listOrders_Success() {
        OrderListResult listRes = new OrderListResult(List.of(new OrderSummary("SP01", "READY_TO_SHIP")), false, "1");
        when(orderClient.getOrderList(eq(88L), any(), any(), eq(20), isNull(), any())).thenReturn(listRes);

        ShopeeOrderDetail detail = new ShopeeOrderDetail("SP01", "READY_TO_SHIP", "VND", BigDecimal.TEN, null, null, null, List.of(), null, null, null, null, null, null, null);
        when(orderClient.getOrderDetail(88L, List.of("SP01"))).thenReturn(List.of(detail));

        ResponseEntity<ApiResponse<List<ShopeeOrderDetail>>> res = controller.listOrders(null, null, 20, null, "ALL");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(1, res.getBody().getData().size());
        assertEquals("SP01", res.getBody().getData().get(0).orderSn());
    }

    @Test
    @DisplayName("syncSingleOrder returns synced payload")
    void syncSingleOrder_Success() {
        ShopeeOrderPayload payload = ShopeeOrderPayload.builder()
                .tenantId(1L)
                .platform("SHOPEE")
                .platformOrderId("SP01")
                .status("TO_SHIP")
                .build();
        when(orderSyncService.syncOrder(connection, "SP01")).thenReturn(payload);

        ResponseEntity<ApiResponse<ShopeeOrderPayload>> res = controller.syncSingleOrder("SP01");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("SP01", res.getBody().getData().getPlatformOrderId());
    }

    @Test
    @DisplayName("batchSyncOrders returns list of synced orders")
    void batchSyncOrders_Success() {
        ShopeeOrderPayload payload = ShopeeOrderPayload.builder().platformOrderId("SP01").status("TO_SHIP").build();
        when(orderSyncService.syncOrdersBatch(eq(connection), any(), any(), any(), eq(50)))
                .thenReturn(List.of(payload));

        ResponseEntity<ApiResponse<List<ShopeeOrderPayload>>> res = controller.batchSyncOrders(null, null, "ALL", 50);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(1, res.getBody().getData().size());
    }

    @Test
    @DisplayName("cancelOrder submits cancellation and returns success map")
    void cancelOrder_Success() {
        when(orderSyncService.cancelOrder(connection, "SP01", "CUSTOMER_REQUEST")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.cancelOrder("SP01", Map.of("cancelReason", "CUSTOMER_REQUEST"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("canceled"));
    }

    @Test
    @DisplayName("handleBuyerCancellation handles accept/reject and returns success map")
    void handleBuyerCancellation_Success() {
        when(orderSyncService.handleBuyerCancellation(connection, "SP01", "ACCEPT")).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.handleBuyerCancellation("SP01", Map.of("operation", "ACCEPT"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue((Boolean) res.getBody().getData().get("success"));
    }
}
