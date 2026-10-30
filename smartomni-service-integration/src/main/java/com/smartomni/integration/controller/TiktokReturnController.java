package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import com.smartomni.integration.service.tiktok.TiktokReturnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller exposing TikTok Shop return, refund, and dispute operations (UC-TK-03).
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/tiktok/returns", "/api/tiktok/returns", "/api/marketplace-connections/tiktok/returns"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'STAFF')")
public class TiktokReturnController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokReturnService returnService;

    /**
     * Lists return and refund requests from TikTok Shop with optional filters and pagination.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ReturnSearchResponse>> listReturns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderId,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String pageToken) {

        MarketplaceConnection connection = tenantConnections.requireConnected();

        ReturnSearchRequest request = ReturnSearchRequest.builder()
                .returnStatus(status)
                .orderId(orderId)
                .pageSize(pageSize)
                .pageToken(pageToken)
                .build();

        ReturnSearchResponse response = returnService.searchReturns(connection.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Retrieves full detail of a single return / refund request by return ID.
     */
    @GetMapping("/{returnId}")
    public ResponseEntity<ApiResponse<TiktokReturnResponse>> getReturnDetail(
            @PathVariable String returnId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        TiktokReturnResponse detail = returnService.getReturnDetail(connection.getId(), returnId);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Approves a buyer's return / refund request.
     */
    @PostMapping("/{returnId}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveReturn(
            @PathVariable String returnId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        boolean success = returnService.approveReturn(connection.getId(), returnId);
        return ResponseEntity.ok(ApiResponse.success("Return approved successfully",
                Map.of("returnId", returnId, "approved", success)));
    }

    /**
     * Rejects a buyer's return / refund request with a specified reason.
     */
    @PostMapping("/{returnId}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rejectReturn(
            @PathVariable String returnId,
            @RequestBody(required = false) Map<String, String> body) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        String rejectReason = body != null && body.containsKey("rejectReason")
                ? body.get("rejectReason")
                : (body != null ? body.get("reject_reason") : "REJECTED_BY_SELLER");

        boolean success = returnService.rejectReturn(connection.getId(), returnId, rejectReason);
        return ResponseEntity.ok(ApiResponse.success("Return rejected successfully",
                Map.of("returnId", returnId, "rejected", success)));
    }
}

