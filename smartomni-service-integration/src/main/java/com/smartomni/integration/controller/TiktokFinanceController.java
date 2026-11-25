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
@RequestMapping("/api/v1/tiktok/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER')")
public class TiktokFinanceController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokFinanceClient financeClient;
    private final com.smartomni.integration.service.tiktok.TiktokFinanceEstimateService estimateService;

    /**
     * Settlement ESTIMATE for {@code [fromDate, toDate)} (epoch seconds, default last 30 days), summed by SmartOmni
     * from COMPLETED orders. Not a TikTok statement: the response has {@code estimated=true} and
     * {@code source=ESTIMATED_FROM_ORDERS}. {@code pageSize}/{@code pageToken} are accepted for compatibility and
     * ignored (every page of orders is read).
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "Estimated TikTok settlements (not a platform statement)",
            description = "Sum of the payment block of COMPLETED orders updated in [fromDate, toDate). TikTok exposes no "
                    + "statement API, so fees and payouts are unknown. estimated=true, source=ESTIMATED_FROM_ORDERS.")
    @GetMapping("/settlements")
    public ResponseEntity<ApiResponse<TiktokSettlementListResult>> getSettlements(
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String pageToken) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Estimating TikTok settlements from orders: connectionId={}, fromDate={}, toDate={}",
                connection.getId(), fromDate, toDate);

        return ResponseEntity.ok(ApiResponse.success(estimateService.estimate(connection.getId(), fromDate, toDate)));
    }

    /**
     * Price breakdown of one TikTok order from Get Price Detail ({@code source=ORDER_PRICE_DETAIL}). Statement-only
     * fields (escrow, fees, refunds) are null. An unknown order is TikTok's 404, answered as 502 TIKTOK_NOT_FOUND.
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "TikTok order price breakdown (from price_detail)",
            description = "Built from GET /order/202407/orders/{id}/price_detail; escrow and fee fields are null because "
                    + "TikTok exposes no finance statement API.")
    @GetMapping("/escrow/{orderId}")
    public ResponseEntity<ApiResponse<TiktokEscrowDetail>> getEscrowDetail(
            @PathVariable String orderId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Fetching TikTok escrow detail: connectionId={}, orderId={}", connection.getId(), orderId);

        TiktokEscrowDetail detail = financeClient.getEscrowDetail(connection.getId(), orderId);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }
}

