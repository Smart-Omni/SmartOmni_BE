package com.smartomni.integration.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.dto.tiktok.TiktokShippingProviderDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller exposing TikTok Shop logistics operations: shipping providers (D3), package shipment (D4),
 * and waybill/shipping label PDF download (D5).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tiktok")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'STAFF')")
public class TiktokLogisticsController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokFulfillmentClient fulfillmentClient;

    /**
     * Returns available shipping providers and services from TikTok Simulator (D3).
     */
    @GetMapping({"/shipping-providers", "/logistics/shipping-providers"})
    public ResponseEntity<ApiResponse<List<TiktokShippingProviderDto>>> getShippingProviders(
            @RequestParam(required = false) String shopId,
            @RequestParam(required = false) String packageId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Fetching TikTok shipping providers: connectionId={}, shopId={}, packageId={}",
                connection.getId(), shopId, packageId);

        List<TiktokShippingProviderDto> providers = fulfillmentClient.getShippingProviders(connection.getId(), packageId);
        return ResponseEntity.ok(ApiResponse.success(providers));
    }

    /**
     * Confirms package shipment / arranges fulfillment with TikTok Fulfillment API (D4).
     */
    @PostMapping({"/packages/{packageId}/ship", "/logistics/ship-package"})
    public ResponseEntity<ApiResponse<TiktokPackageResponse>> shipPackage(
            @PathVariable(required = false) String packageId,
            @RequestBody(required = false) TiktokShipPackageRequest request) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        String resolvedPackageId = firstNonBlank(packageId, request != null ? request.getPackageId() : null);
        if (resolvedPackageId == null) {
            throw new BusinessException("packageId is required", HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_ID");
        }

        String handoverMethod = (request != null && request.getHandoverMethod() != null && !request.getHandoverMethod().isBlank())
                ? request.getHandoverMethod().trim()
                : "PICKUP";
        String shippingServiceId = request != null ? request.getShippingServiceId() : null;

        log.info("Arranging TikTok shipment: connectionId={}, packageId={}, handoverMethod={}, shippingServiceId={}",
                connection.getId(), resolvedPackageId, handoverMethod, shippingServiceId);

        ShipPackageRequest shipRequest = ShipPackageRequest.builder()
                .handoverMethod(handoverMethod)
                .shippingServiceId(shippingServiceId)
                .build();

        TiktokPackageResponse response = fulfillmentClient.shipPackage(connection.getId(), resolvedPackageId, shipRequest);
        return ResponseEntity.ok(ApiResponse.success("Package shipped successfully", response));
    }

    /**
     * Downloads the shipping label / waybill PDF directly from TikTok Simulator and streams it
     * with Content-Type: application/pdf. Errors throw BusinessException with SHIPPING_LABEL_UNAVAILABLE (D5).
     */
    @GetMapping({
            "/packages/{packageId}/shipping-label/download",
            "/orders/{orderId}/shipping-label/download",
            "/logistics/shipping-label/download"
    })
    public ResponseEntity<byte[]> downloadShippingLabel(
            @PathVariable(required = false) String packageId,
            @PathVariable(required = false) String orderId,
            @RequestParam(required = false) String shopId,
            @RequestParam(name = "orderId", required = false) String orderIdParam,
            @RequestParam(name = "packageId", required = false) String packageIdParam,
            @RequestParam(defaultValue = "SHIPPING_LABEL") String documentType) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        String resolvedPackageId = firstNonBlank(packageId, packageIdParam);
        String resolvedOrderId = firstNonBlank(orderId, orderIdParam);

        byte[] pdfBytes;
        String labelId;
        if (resolvedPackageId != null) {
            labelId = resolvedPackageId;
            log.info("Downloading TikTok shipping label by packageId: connectionId={}, packageId={}",
                    connection.getId(), resolvedPackageId);
            pdfBytes = fulfillmentClient.downloadShippingLabelByPackage(connection.getId(), resolvedPackageId, documentType);
        } else if (resolvedOrderId != null) {
            labelId = resolvedOrderId;
            log.info("Downloading TikTok shipping label by orderId: connectionId={}, orderId={}",
                    connection.getId(), resolvedOrderId);
            pdfBytes = fulfillmentClient.downloadShippingLabelByOrder(connection.getId(), resolvedOrderId, documentType);
        } else {
            throw new BusinessException("packageId or orderId is required to download shipping label",
                    HttpStatus.BAD_REQUEST, TiktokFulfillmentClient.SHIPPING_LABEL_UNAVAILABLE_CODE);
        }

        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new BusinessException("Shipping label PDF is empty",
                    HttpStatus.BAD_GATEWAY, TiktokFulfillmentClient.SHIPPING_LABEL_UNAVAILABLE_CODE);
        }

        String filename = "TikTok-Label-" + labelId + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdfBytes);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }

    /**
     * DTO for the ship-package request body.
     */
    public static class TiktokShipPackageRequest {

        @JsonAlias({"shop_id"})
        private String shopId;

        @JsonAlias({"order_id"})
        private String orderId;

        @JsonAlias({"package_id"})
        private String packageId;

        @JsonAlias({"shipping_service_id"})
        private String shippingServiceId;

        @JsonAlias({"handover_method"})
        private String handoverMethod;

        public TiktokShipPackageRequest() {}

        public String getShopId() { return shopId; }
        public void setShopId(String shopId) { this.shopId = shopId; }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getPackageId() { return packageId; }
        public void setPackageId(String packageId) { this.packageId = packageId; }

        public String getShippingServiceId() { return shippingServiceId; }
        public void setShippingServiceId(String shippingServiceId) { this.shippingServiceId = shippingServiceId; }

        public String getHandoverMethod() { return handoverMethod; }
        public void setHandoverMethod(String handoverMethod) { this.handoverMethod = handoverMethod; }
    }
}
