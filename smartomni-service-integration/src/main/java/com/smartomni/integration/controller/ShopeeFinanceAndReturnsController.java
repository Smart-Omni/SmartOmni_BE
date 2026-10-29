package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.shopee.ShopeeFinanceClient.EscrowDetail;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ReturnListResult;
import com.smartomni.integration.client.shopee.ShopeeReturnsClient.ShopeeReturnDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.finance.ShopeeFinanceAndReturnsService;
import com.smartomni.integration.service.finance.ShopeeFinanceAndReturnsService.DisputeReturnRequest;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller exposing Shopee escrow financial reconciliations and return/refund dispute actions (UC-12, Phase 6).
 */
@Slf4j
@RestController
@RequestMapping("/api/marketplace-connections/shopee")
@RequiredArgsConstructor
public class ShopeeFinanceAndReturnsController {

    private final ShopeeTenantConnections tenantConnections;
    private final ShopeeFinanceAndReturnsService financeAndReturnsService;

    /**
     * Retrieves the itemized financial escrow breakdown for an order.
     */
    @GetMapping("/orders/{orderSn}/escrow")
    public ResponseEntity<ApiResponse<EscrowDetail>> getEscrowDetail(@PathVariable String orderSn) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        EscrowDetail detail = financeAndReturnsService.getEscrowDetail(connection, orderSn);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Lists buyer returns and refund requests from Shopee.
     */
    @GetMapping("/returns")
    public ResponseEntity<ApiResponse<ReturnListResult>> listReturns(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        ReturnListResult result = financeAndReturnsService.listReturns(connection, pageNo, pageSize, status);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Retrieves full detail of a return request including proof images and reverse shipping fees.
     */
    @GetMapping("/returns/{returnSn}")
    public ResponseEntity<ApiResponse<ShopeeReturnDetail>> getReturnDetail(@PathVariable String returnSn) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        ShopeeReturnDetail detail = financeAndReturnsService.getReturnDetail(connection, returnSn);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Confirms and accepts a buyer's return / refund request.
     */
    @PostMapping("/returns/{returnSn}/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmReturn(@PathVariable String returnSn) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        boolean success = financeAndReturnsService.confirmReturn(connection, returnSn);
        return ResponseEntity.ok(ApiResponse.success("Return confirmed successfully", Map.of("returnSn", returnSn, "confirmed", success)));
    }

    /**
     * Submits a seller dispute against a return request with text reason and photo proofs.
     */
    @PostMapping("/returns/{returnSn}/dispute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disputeReturn(
            @PathVariable String returnSn,
            @RequestBody DisputeReturnRequest request) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        boolean success = financeAndReturnsService.disputeReturn(connection, returnSn, request);
        return ResponseEntity.ok(ApiResponse.success("Dispute submitted successfully", Map.of("returnSn", returnSn, "disputed", success)));
    }
}
