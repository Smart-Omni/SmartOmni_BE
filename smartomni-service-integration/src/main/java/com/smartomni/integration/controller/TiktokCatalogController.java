package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.service.catalog.ImportReport;
import com.smartomni.integration.service.catalog.TiktokCatalogService;
import com.smartomni.integration.service.catalog.TiktokPriceSyncService;
import com.smartomni.integration.service.catalog.TiktokProductImportService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Admin/Manager/Staff triggers for TikTok catalog sync, import, and push (stock & price updates).
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/tiktok/products", "/api/tiktok/products", "/api/marketplace-connections/tiktok-shop"})
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'MANAGER', 'STAFF')")
public class TiktokCatalogController {

    private final TiktokProductImportService importService;
    private final TiktokPriceSyncService priceSyncService;
    private final TiktokCatalogService catalogService;

    public TiktokCatalogController(TiktokProductImportService importService,
                                   TiktokPriceSyncService priceSyncService) {
        this(importService, priceSyncService, null);
    }

    @Autowired
    public TiktokCatalogController(TiktokProductImportService importService,
                                   TiktokPriceSyncService priceSyncService,
                                   TiktokCatalogService catalogService) {
        this.importService = importService;
        this.priceSyncService = priceSyncService;
        this.catalogService = catalogService;
    }

    @PostMapping("/products/import")
    public ApiResponse<ImportReport> importProducts() {
        return ApiResponse.success(importService.importForCurrentTenant());
    }

    @GetMapping("/products/import-runs")
    public ApiResponse<List<ImportReport>> importRuns(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(importService.recentRuns(limit));
    }

    @PostMapping("/warehouses/sync")
    public ApiResponse<Map<String, Integer>> syncWarehouses() {
        return ApiResponse.success(Map.of("warehouses", importService.syncWarehousesForCurrentTenant()));
    }

    @PostMapping("/prices/sync")
    public ApiResponse<TiktokPriceSyncService.Result> syncPrices() {
        return ApiResponse.success(priceSyncService.syncForCurrentTenant());
    }

    /**
     * Pushes stock update to TikTok Shop for a specific product SKU (PUT /api/v1/tiktok/products/stock).
     */
    @PutMapping("/stock")
    public ApiResponse<Map<String, Object>> updateStock(@RequestBody TiktokStockUpdateRequest request) {
        log.info("Request to update TikTok stock: shopId={}, productId={}, skuId={}, qty={}",
                request.getShopId(), request.getProductId(), request.getSkuId(), request.getQuantity());

        if (catalogService != null) {
            catalogService.updateStock(request.getShopId(), request.getProductId(), request.getSkuId(), request.getQuantity());
        }

        return ApiResponse.success("Stock updated successfully",
                Map.of("productId", request.getProductId() != null ? request.getProductId() : "",
                        "skuId", request.getSkuId() != null ? request.getSkuId() : "",
                        "quantity", request.getQuantity() != null ? request.getQuantity() : 0));
    }

    /**
     * Pushes price update to TikTok Shop for a specific product SKU (PUT /api/v1/tiktok/products/price).
     */
    @PutMapping("/price")
    public ApiResponse<Map<String, Object>> updatePrice(@RequestBody TiktokPriceUpdateRequest request) {
        log.info("Request to update TikTok price: shopId={}, productId={}, skuId={}, origPrice={}, salePrice={}",
                request.getShopId(), request.getProductId(), request.getSkuId(),
                request.getOriginalPrice(), request.getSalePrice());

        if (catalogService != null) {
            catalogService.updatePrice(request.getShopId(), request.getProductId(), request.getSkuId(),
                    request.getOriginalPrice(), request.getSalePrice());
        }

        BigDecimal finalPrice = request.getOriginalPrice() != null
                ? request.getOriginalPrice()
                : (request.getSalePrice() != null ? request.getSalePrice() : BigDecimal.ZERO);

        return ApiResponse.success("Price updated successfully",
                Map.of("productId", request.getProductId() != null ? request.getProductId() : "",
                        "skuId", request.getSkuId() != null ? request.getSkuId() : "",
                        "price", finalPrice));
    }

    /**
     * DTO for stock update request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TiktokStockUpdateRequest {
        private String shopId;
        private String productId;
        private String skuId;
        private Integer quantity;
    }

    /**
     * DTO for price update request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TiktokPriceUpdateRequest {
        private String shopId;
        private String productId;
        private String skuId;
        private BigDecimal originalPrice;
        private BigDecimal salePrice;
    }
}
