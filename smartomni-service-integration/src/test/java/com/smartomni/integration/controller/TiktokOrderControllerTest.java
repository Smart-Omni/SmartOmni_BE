package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.controller.TiktokOrderController.TiktokOrderSyncRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderSyncResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
import com.smartomni.integration.service.tiktok.TiktokOrderSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokOrderController Unit Tests")
class TiktokOrderControllerTest {

    private TiktokTenantConnections tenantConnections;
    private TiktokOrderClient orderClient;
    private TiktokOrderIngestionService orderIngestionService;
    private TiktokOrderSyncService orderSyncService;
    private TiktokOrderController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        orderClient = mock(TiktokOrderClient.class);
        orderIngestionService = mock(TiktokOrderIngestionService.class);
        orderSyncService = mock(TiktokOrderSyncService.class);

        controller = new TiktokOrderController(tenantConnections, orderClient, orderIngestionService, orderSyncService);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("listOrders returns OrderSearchResponse with orders")
    void listOrders_Success() {
        TiktokOrderDetailResponse order = new TiktokOrderDetailResponse();
        order.setOrderId("TK-ORDER-001");
        order.setOrderStatus("AWAITING_SHIPMENT");

        OrderSearchResponse searchResponse = OrderSearchResponse.builder()
                .orders(List.of(order))
                .totalCount(1)
                .build();

        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class))).thenReturn(searchResponse);

        ResponseEntity<ApiResponse<OrderSearchResponse>> res =
                controller.listOrders("AWAITING_SHIPMENT", null, null, null, 20, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertNotNull(res.getBody().getData());
        assertEquals(1, res.getBody().getData().getOrders().size());
        assertEquals("TK-ORDER-001", res.getBody().getData().getOrders().get(0).getOrderId());
    }

    @Test
    @DisplayName("listOrders forwards orderId filter to TiktokOrderClient (D1)")
    void listOrders_WithOrderId_ForwardsFilter() {
        TiktokOrderDetailResponse order = new TiktokOrderDetailResponse();
        order.setOrderId("TK-ORDER-999");

        OrderSearchResponse searchResponse = OrderSearchResponse.builder()
                .orders(List.of(order))
                .totalCount(1)
                .build();

        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class))).thenReturn(searchResponse);

        ResponseEntity<ApiResponse<OrderSearchResponse>> res =
                controller.listOrders(null, "TK-ORDER-999", null, null, 20, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("TK-ORDER-999", res.getBody().getData().getOrders().get(0).getOrderId());

        ArgumentCaptor<OrderSearchRequest> captor = ArgumentCaptor.forClass(OrderSearchRequest.class);
        verify(orderClient).searchOrders(eq(77L), captor.capture());
        assertEquals("TK-ORDER-999", captor.getValue().getOrderId());
    }

    @Test
    @DisplayName("listOrders returns empty response when no orders found")
    void listOrders_EmptyResult() {
        OrderSearchResponse emptyResponse = OrderSearchResponse.builder()
                .orders(List.of())
                .totalCount(0)
                .build();

        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class))).thenReturn(emptyResponse);

        ResponseEntity<ApiResponse<OrderSearchResponse>> res =
                controller.listOrders(null, null, null, null, 20, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertTrue(res.getBody().getData().getOrders().isEmpty());
    }

    @Test
    @DisplayName("getOrderDetail returns list of TiktokOrderDetailResponse")
    void getOrderDetail_Success() {
        TiktokOrderDetailResponse order = new TiktokOrderDetailResponse();
        order.setOrderId("TK-ORDER-001");
        order.setOrderStatus("DELIVERED");

        when(orderClient.getOrderDetails(eq(77L), eq(List.of("TK-ORDER-001")))).thenReturn(List.of(order));

        ResponseEntity<ApiResponse<List<TiktokOrderDetailResponse>>> res =
                controller.getOrderDetail("TK-ORDER-001");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(1, res.getBody().getData().size());
        assertEquals("TK-ORDER-001", res.getBody().getData().get(0).getOrderId());
    }

    @Test
    @DisplayName("syncOrders delegates to TiktokOrderSyncService and returns sync report (D2)")
    void syncOrders_Success() {
        TiktokOrderDetailResponse order = new TiktokOrderDetailResponse();
        order.setOrderId("TK-SYNC-001");
        order.setOrderStatus("AWAITING_SHIPMENT");

        TiktokOrderSyncResponse syncResponse = TiktokOrderSyncResponse.builder()
                .totalFetched(1)
                .createdCount(1)
                .updatedCount(0)
                .orders(List.of(order))
                .build();

        when(orderSyncService.syncOrders(connection, 1700000000L, 1700086400L)).thenReturn(syncResponse);

        TiktokOrderSyncRequest syncRequest = new TiktokOrderSyncRequest();
        syncRequest.setShopId("SHOP-001");
        syncRequest.setFromDate(1700000000L);
        syncRequest.setToDate(1700086400L);

        ResponseEntity<ApiResponse<TiktokOrderSyncResponse>> res = controller.syncOrders(syncRequest);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Orders synced successfully", res.getBody().getMessage());
        assertEquals(1, res.getBody().getData().getTotalFetched());
        assertEquals(1, res.getBody().getData().getCreatedCount());
        assertEquals(0, res.getBody().getData().getUpdatedCount());
    }

    @Test
    @DisplayName("syncOrders with null request still succeeds")
    void syncOrders_NullRequest_Success() {
        TiktokOrderSyncResponse emptyResponse = TiktokOrderSyncResponse.builder()
                .totalFetched(0)
                .createdCount(0)
                .updatedCount(0)
                .orders(List.of())
                .build();

        when(orderSyncService.syncOrders(connection, null, null)).thenReturn(emptyResponse);

        ResponseEntity<ApiResponse<TiktokOrderSyncResponse>> res = controller.syncOrders(null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        verify(orderSyncService).syncOrders(connection, null, null);
    }
}

