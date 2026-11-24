package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokCancellationClient;
import com.smartomni.integration.dto.tiktok.CancellationSearchRequest;
import com.smartomni.integration.dto.tiktok.CancellationSearchResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller exposing TikTok Shop order cancellation and buyer cancellation request operations (UC-TK-04).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tiktok/cancellations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'STAFF')")
public class TiktokCancellationController {

    /** TikTok lets a seller cancel only these order statuses. */
    static final java.util.Set<String> SELLER_CANCELLABLE = java.util.Set.of("UNPAID", "AWAITING_SHIPMENT");

    private final TiktokTenantConnections tenantConnections;
    private final TiktokCancellationClient cancellationClient;
    private final com.smartomni.integration.client.tiktok.TiktokOrderClient orderClient;

    /**
     * Lists buyer-initiated order cancellation requests from TikTok Simulator for the current shop (D6).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<CancellationSearchResponse>> listCancellations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderId,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String pageToken) {

        MarketplaceConnection connection = tenantConnections.requireConnected();

        CancellationSearchRequest request = CancellationSearchRequest.builder()
                .cancelStatus(status)
                .orderId(orderId)
                .pageSize(pageSize)
                .pageToken(pageToken)
                .build();

        CancellationSearchResponse response = cancellationClient.searchCancellations(connection.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Seller cancels an order on TikTok Shop (POST /return_refund/202309/cancellations). Allowed only while the order
     * is UNPAID or AWAITING_SHIPMENT; otherwise 409 {@code TIKTOK_CANCEL_NOT_ALLOWED} without calling TikTok.
     */
    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelOrder(
            @RequestBody TiktokCancelOrderRequest request) {

        if (request == null || request.getOrderId() == null || request.getOrderId().isBlank()) {
            throw new com.smartomni.common.exception.BusinessException("orderId is required",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ORDER_ID");
        }
        MarketplaceConnection connection = tenantConnections.requireConnected();
        String orderId = request.getOrderId().trim();
        log.info("Actively canceling TikTok order: connectionId={}, orderId={}, reason={}",
                connection.getId(), orderId, request.getCancelReason());

        var order = orderClient.getOrderDetail(connection.getId(), orderId);
        if (order == null) {
            throw new com.smartomni.common.exception.BusinessException("TikTok order not found: " + orderId,
                    org.springframework.http.HttpStatus.NOT_FOUND, "TIKTOK_ORDER_NOT_FOUND");
        }
        if (!SELLER_CANCELLABLE.contains(order.getOrderStatus())) {
            throw new com.smartomni.common.exception.BusinessException(
                    "A seller can cancel only UNPAID or AWAITING_SHIPMENT orders; order is " + order.getOrderStatus(),
                    org.springframework.http.HttpStatus.CONFLICT, "TIKTOK_CANCEL_NOT_ALLOWED");
        }

        var result = cancellationClient.cancelOrder(connection.getId(), orderId, request.getCancelReason(),
                request.getOrderLineItemIds());

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("canceled", true);
        data.put("cancelId", result.cancelId() != null ? result.cancelId() : "");
        data.put("cancelStatus", result.cancelStatus() != null ? result.cancelStatus() : "");
        return ResponseEntity.ok(ApiResponse.success("Order cancellation submitted", data));
    }

    /**
     * Seller approves a buyer-initiated order cancellation request.
     */
    @PostMapping("/{cancelId}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveCancellation(
            @PathVariable String cancelId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Approving buyer cancellation: connectionId={}, cancelId={}", connection.getId(), cancelId);

        boolean success = cancellationClient.approveCancellation(connection.getId(), cancelId);

        return ResponseEntity.ok(ApiResponse.success("Cancellation approved successfully",
                Map.of("cancelId", cancelId, "approved", success)));
    }

    /**
     * Seller rejects a buyer-initiated order cancellation request.
     */
    @PostMapping("/{cancelId}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rejectCancellation(
            @PathVariable String cancelId,
            @RequestBody(required = false) Map<String, String> body) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        String rejectReason = body != null && body.containsKey("rejectReason")
                ? body.get("rejectReason")
                : (body != null ? body.get("reject_reason") : "REJECTED_BY_SELLER");

        log.info("Rejecting buyer cancellation: connectionId={}, cancelId={}, reason={}",
                connection.getId(), cancelId, rejectReason);

        boolean success = cancellationClient.rejectCancellation(connection.getId(), cancelId, rejectReason);

        return ResponseEntity.ok(ApiResponse.success("Cancellation rejected successfully",
                Map.of("cancelId", cancelId, "rejected", success)));
    }

    /**
     * DTO for active order cancellation request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TiktokCancelOrderRequest {
        private String shopId;
        private String orderId;
        private String cancelReason;
        /** Optional: cancel only these order line items. */
        private java.util.List<String> orderLineItemIds;
    }
}

