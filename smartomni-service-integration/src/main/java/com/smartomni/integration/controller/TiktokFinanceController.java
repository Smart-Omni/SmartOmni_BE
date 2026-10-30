package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokEscrowDetail;
import com.smartomni.integration.client.tiktok.TiktokFinanceClient.TiktokSettlementListResult;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Controller exposing TikTok Shop finance, settlement statements, and itemized order escrow (UC-TK-05).
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/tiktok/finance", "/api/tiktok/finance", "/api/marketplace-connections/tiktok/finance"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'STAFF')")
public class TiktokFinanceController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokFinanceClient financeClient;

    /**
     * Lists financial settlements / statements within a specified date range.
     */
    @GetMapping("/settlements")
    public ResponseEntity<ApiResponse<TiktokSettlementListResult>> getSettlements(
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String pageToken) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Fetching TikTok settlements: connectionId={}, fromDate={}, toDate={}, pageSize={}",
                connection.getId(), fromDate, toDate, pageSize);

        TiktokSettlementListResult result = financeClient.getSettlements(
                connection.getId(), fromDate, toDate, pageSize, pageToken);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Retrieves itemized financial escrow breakdown for a specific TikTok order.
     */
    @GetMapping("/escrow/{orderId}")
    public ResponseEntity<ApiResponse<TiktokEscrowDetail>> getEscrowDetail(
            @PathVariable String orderId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Fetching TikTok escrow detail: connectionId={}, orderId={}", connection.getId(), orderId);

        TiktokEscrowDetail detail = financeClient.getEscrowDetail(connection.getId(), orderId);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }
}

