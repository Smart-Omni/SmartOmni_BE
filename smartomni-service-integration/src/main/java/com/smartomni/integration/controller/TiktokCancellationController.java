package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokCancellationClient;
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
@RequestMapping({"/api/v1/tiktok/cancellations", "/api/tiktok/cancellations", "/api/marketplace-connections/tiktok/cancellations"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'STAFF')")
public class TiktokCancellationController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokCancellationClient cancellationClient;

    /**
     * Seller actively requests to cancel an order on TikTok Shop.
     */
    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelOrder(
            @RequestBody TiktokCancelOrderRequest request) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Actively canceling TikTok order: connectionId={}, orderId={}, reason={}",
                connection.getId(), request.getOrderId(), request.getCancelReason());

        boolean success = cancellationClient.cancelOrder(connection.getId(),
                request.getOrderId(), request.getCancelReason());

        return ResponseEntity.ok(ApiResponse.success("Order cancellation submitted",
                Map.of("orderId", request.getOrderId() != null ? request.getOrderId() : "", "canceled", success)));
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
    }
}

