package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.controller.TiktokOrderController.TiktokOrderSyncRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    private TiktokOrderController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        orderClient = mock(TiktokOrderClient.class);
        orderIngestionService = mock(TiktokOrderIngestionService.class);

        controller = new TiktokOrderController(tenantConnections, orderClient, orderIngestionService);

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
    @DisplayName("syncOrders triggers manual sync and returns synced orders")
    void syncOrders_Success() {
        TiktokOrderDetailResponse order = new TiktokOrderDetailResponse();
        order.setOrderId("TK-SYNC-001");
        order.setOrderStatus("AWAITING_SHIPMENT");

        OrderSearchResponse syncResponse = OrderSearchResponse.builder()
                .orders(List.of(order))
                .totalCount(1)
                .build();

        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class))).thenReturn(syncResponse);

        TiktokOrderSyncRequest syncRequest = new TiktokOrderSyncRequest();
        syncRequest.setShopId("SHOP-001");
        syncRequest.setFromDate(1700000000L);
        syncRequest.setToDate(1700086400L);

        ResponseEntity<ApiResponse<OrderSearchResponse>> res = controller.syncOrders(syncRequest);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Orders synced successfully", res.getBody().getMessage());
        assertEquals(1, res.getBody().getData().getOrders().size());
    }

    @Test
    @DisplayName("syncOrders with null fromDate and toDate still succeeds")
    void syncOrders_NullDates_Success() {
        OrderSearchResponse emptyResponse = OrderSearchResponse.builder()
                .orders(List.of())
                .totalCount(0)
                .build();

        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class))).thenReturn(emptyResponse);

        TiktokOrderSyncRequest syncRequest = new TiktokOrderSyncRequest();
        syncRequest.setShopId("SHOP-002");

        ResponseEntity<ApiResponse<OrderSearchResponse>> res = controller.syncOrders(syncRequest);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        verify(orderClient).searchOrders(eq(77L), any(OrderSearchRequest.class));
    }
}

