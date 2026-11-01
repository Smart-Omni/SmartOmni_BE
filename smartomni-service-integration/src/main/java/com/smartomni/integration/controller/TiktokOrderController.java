package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller exposing TikTok Shop order operations, manual sync trigger, and order detail lookup (UC-TK-01).
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/tiktok/orders", "/api/tiktok/orders", "/api/marketplace-connections/tiktok/orders"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'STAFF')")
public class TiktokOrderController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokOrderClient orderClient;
    private final TiktokOrderIngestionService orderIngestionService;

    /**
     * Lists orders from TikTok Shop with optional filters (status, date range) and pagination.
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
     * Triggers a manual order sync from TikTok Shop for the specified shop and date range.
     */
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<OrderSearchResponse>> syncOrders(
            @RequestBody TiktokOrderSyncRequest syncRequest) {

        MarketplaceConnection connection = tenantConnections.requireConnected();

        OrderSearchRequest request = OrderSearchRequest.builder()
                .createTimeFrom(syncRequest.getFromDate())
                .createTimeTo(syncRequest.getToDate())
                .pageSize(50)
                .build();

        OrderSearchResponse response = orderClient.searchOrders(connection.getId(), request);
        log.info("Manual TikTok order sync completed: connectionId={}, shopId={}, count={}",
                connection.getId(), syncRequest.getShopId(),
                response.getOrders() != null ? response.getOrders().size() : 0);

        return ResponseEntity.ok(ApiResponse.success("Orders synced successfully", response));
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

