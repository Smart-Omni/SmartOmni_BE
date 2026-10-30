package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller exposing TikTok Shop logistics operations: shipping providers, package shipment,
 * and waybill/shipping label PDF download (UC-TK-02).
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/tiktok/logistics", "/api/tiktok/logistics", "/api/marketplace-connections/tiktok/logistics"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'STAFF')")
public class TiktokLogisticsController {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokFulfillmentClient fulfillmentClient;

    /**
     * Returns available shipping providers / document types supported by the tenant's TikTok Shop connection.
     */
    @GetMapping("/shipping-providers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getShippingProviders(
            @RequestParam(required = false) String shopId) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Fetching shipping providers: connectionId={}, shopId={}, platform={}",
                connection.getId(), shopId, connection.getPlatform());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connectionId", connection.getId());
        result.put("platform", connection.getPlatform());
        result.put("shopId", shopId);
        result.put("documentTypes", List.of("SL_PDF", "WAYBILL_PDF"));

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Confirms package shipment / arranges fulfillment with TikTok Fulfillment API.
     */
    @PostMapping("/ship-package")
    public ResponseEntity<ApiResponse<TiktokPackageResponse>> shipPackage(
            @RequestBody TiktokShipPackageRequest request) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Arranging TikTok shipment: connectionId={}, orderId={}, packageId={}",
                connection.getId(), request.getOrderId(), request.getPackageId());

        ShipPackageRequest shipRequest = ShipPackageRequest.builder().build();
        TiktokPackageResponse response = fulfillmentClient.shipPackage(connection.getId(),
                request.getPackageId(), shipRequest);

        return ResponseEntity.ok(ApiResponse.success("Package shipped successfully", response));
    }

    /**
     * Downloads the shipping label / waybill PDF (Thermal 100x150mm) and streams it directly
     * to the frontend with Content-Type: application/pdf.
     */
    @GetMapping(value = "/shipping-label/download", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadShippingLabel(
            @RequestParam(required = false) String shopId,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String packageId,
            @RequestParam(defaultValue = "SL_PDF") String documentType) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        String resolvedPackageId = packageId != null ? packageId : orderId;
        log.info("Downloading TikTok shipping label: connectionId={}, packageId={}, documentType={}",
                connection.getId(), resolvedPackageId, documentType);

        ShippingDocumentResponse docResponse = fulfillmentClient.getShippingDocuments(
                connection.getId(), resolvedPackageId, documentType);

        String labelUrl = docResponse.getDocUrl() != null ? docResponse.getDocUrl() : docResponse.getFileUrl();
        byte[] pdfBytes = fetchPdfBytes(labelUrl);
        String filename = "TikTok-Label-" + resolvedPackageId + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdfBytes);
    }

    /**
     * Fetches raw PDF bytes from the given CDN pre-signed URL returned by TikTok.
     */
    private byte[] fetchPdfBytes(String url) {
        if (url == null || url.isBlank()) {
            return new byte[0];
        }
        try {
            RestTemplate restTemplate = new RestTemplate();
            return restTemplate.getForObject(url, byte[].class);
        } catch (Exception ex) {
            log.error("Failed to download shipping label PDF from URL: {}", url, ex);
            return new byte[0];
        }
    }

    /**
     * DTO for the ship-package request body.
     */
    public static class TiktokShipPackageRequest {

        private String shopId;
        private String orderId;
        private String packageId;
        private String shippingServiceId;

        public TiktokShipPackageRequest() {}

        public String getShopId() { return shopId; }
        public void setShopId(String shopId) { this.shopId = shopId; }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getPackageId() { return packageId; }
        public void setPackageId(String packageId) { this.packageId = packageId; }

        public String getShippingServiceId() { return shippingServiceId; }
        public void setShippingServiceId(String shippingServiceId) { this.shippingServiceId = shippingServiceId; }
    }
}
