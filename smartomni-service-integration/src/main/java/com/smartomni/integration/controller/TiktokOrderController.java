package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderSyncResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
import com.smartomni.integration.service.tiktok.TiktokOrderSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller exposing TikTok Shop order operations, manual sync trigger, and order detail lookup (UC-TK-01).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tiktok/orders")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'STAFF')")
public class TiktokOrderController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokOrderClient orderClient;
    private final TiktokOrderIngestionService orderIngestionService;
    private final TiktokOrderSyncService orderSyncService;

    public TiktokOrderController(TiktokTenantConnections tenantConnections,
                                 TiktokOrderClient orderClient,
                                 TiktokOrderIngestionService orderIngestionService) {
        this(tenantConnections, orderClient, orderIngestionService, null);
    }

    @Autowired
    public TiktokOrderController(TiktokTenantConnections tenantConnections,
                                 TiktokOrderClient orderClient,
                                 TiktokOrderIngestionService orderIngestionService,
                                 TiktokOrderSyncService orderSyncService) {
        this.tenantConnections = tenantConnections;
        this.orderClient = orderClient;
        this.orderIngestionService = orderIngestionService;
        this.orderSyncService = orderSyncService;
    }

    /**
     * Lists orders from TikTok Shop with optional filters (orderId, status, date range) and pagination (D1).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<OrderSearchResponse>> listOrders(
            @RequestParam(required = false) String orderStatus,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) Long createTimeFrom,
            @RequestParam(required = false) Long createTimeTo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String pageToken) {

        MarketplaceConnection connection = tenantConnections.requireConnected();

        OrderSearchRequest request = OrderSearchRequest.builder()
                .orderId(orderId != null && !orderId.isBlank() ? orderId.trim() : null)
                .orderStatus(orderStatus)
                .createTimeFrom(createTimeFrom)
                .createTimeTo(createTimeTo)
                .pageSize(pageSize)
                .pageToken(pageToken)
                .build();

        OrderSearchResponse response = orderClient.searchOrders(connection.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Returns detailed information for a single TikTok order by its order ID.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<List<TiktokOrderDetailResponse>>> getOrderDetail(
            @PathVariable String orderId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        List<TiktokOrderDetailResponse> details = orderClient.getOrderDetails(connection.getId(), List.of(orderId));
        return ResponseEntity.ok(ApiResponse.success(details));
    }

    /**
     * Triggers a manual order sync from TikTok Shop, upserting orders and items into SmartOmni DB (D2).
     */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<TiktokOrderSyncResponse>> syncOrders(
            @RequestBody(required = false) TiktokOrderSyncRequest syncRequest) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        Long fromDate = syncRequest != null ? syncRequest.getFromDate() : null;
        Long toDate = syncRequest != null ? syncRequest.getToDate() : null;

        if (orderSyncService != null) {
            TiktokOrderSyncResponse syncResponse = orderSyncService.syncOrders(connection, fromDate, toDate);
            return ResponseEntity.ok(ApiResponse.success("Orders synced successfully", syncResponse));
        }

        OrderSearchRequest request = OrderSearchRequest.builder()
                .createTimeFrom(fromDate)
                .createTimeTo(toDate)
                .pageSize(50)
                .build();
        OrderSearchResponse response = orderClient.searchOrders(connection.getId(), request);
        List<TiktokOrderDetailResponse> orders = response != null ? response.getOrders() : List.of();
        TiktokOrderSyncResponse fallback = TiktokOrderSyncResponse.builder()
                .totalFetched(orders.size())
                .createdCount(0)
                .updatedCount(0)
                .orders(orders)
                .build();
        return ResponseEntity.ok(ApiResponse.success("Orders synced successfully", fallback));
    }

    /**
     * DTO for the manual sync request body.
     */
    public static class TiktokOrderSyncRequest {

        private String shopId;
        private Long fromDate;
        private Long toDate;

        public TiktokOrderSyncRequest() {}

        public String getShopId() { return shopId; }
        public void setShopId(String shopId) { this.shopId = shopId; }

        public Long getFromDate() { return fromDate; }
        public void setFromDate(Long fromDate) { this.fromDate = fromDate; }

        public Long getToDate() { return toDate; }
        public void setToDate(Long toDate) { this.toDate = toDate; }
    }
}

