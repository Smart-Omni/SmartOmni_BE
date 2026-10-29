package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.ChannelDetail;
import com.smartomni.integration.client.shopee.ShopeeLogisticsClient.ShippingParameter;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService.ShipConfirmResult;
import com.smartomni.integration.service.logistics.ShopeeLogisticsService.ShipOrderRequest;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller exposing Shopee logistics channels, shipping parameters, fulfillment, and thermal AWB PDF download (UC-10, Phase 5).
 */
@Slf4j
@RestController
@RequestMapping("/api/marketplace-connections/shopee")
@RequiredArgsConstructor
public class ShopeeLogisticsController {

    private final ShopeeTenantConnections tenantConnections;
    private final ShopeeLogisticsClient logisticsClient;
    private final ShopeeLogisticsService logisticsService;

    /**
     * Lists logistics channels supported by Shopee (SPX Express, Instant, Economy, etc.).
     */
    @GetMapping("/logistics/channels")
    public ResponseEntity<ApiResponse<List<ChannelDetail>>> getChannelList() {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        List<ChannelDetail> channels = logisticsClient.getChannelList(connection.getId());
        return ResponseEntity.ok(ApiResponse.success(channels));
    }

    /**
     * Retrieves shipping parameters (pickup address, time slots, dropoff points) for an order.
     */
    @GetMapping("/orders/{orderSn}/shipping-parameter")
    public ResponseEntity<ApiResponse<ShippingParameter>> getShippingParameter(@PathVariable String orderSn) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        ShippingParameter param = logisticsService.getShippingParameter(connection, orderSn);
        return ResponseEntity.ok(ApiResponse.success(param));
    }

    /**
     * Confirms order fulfillment (pickup dispatch or dropoff), assigns tracking number, and starts 48h SLA timer.
     */
    @PostMapping("/orders/{orderSn}/ship")
    public ResponseEntity<ApiResponse<ShipConfirmResult>> shipOrder(
            @PathVariable String orderSn,
            @RequestBody ShipOrderRequest request) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        ShipConfirmResult result = logisticsService.confirmShipment(connection, orderSn, request);
        return ResponseEntity.ok(ApiResponse.success("Order shipped successfully", result));
    }

    /**
     * Downloads binary Airway Bill (AWB) Thermal PDF (100x150mm) with Code128 barcodes.
     */
    @GetMapping(value = "/orders/{orderSn}/shipping-document", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadShippingDocument(@PathVariable String orderSn) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        byte[] pdfBytes = logisticsService.downloadAwb(connection, orderSn);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"AWB-" + orderSn + ".pdf\"")
                .body(pdfBytes);
    }
}
