package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.service.catalog.ImportReport;
import com.smartomni.integration.service.catalog.ShopeePriceSyncService;
import com.smartomni.integration.service.catalog.ShopeeProductImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tenant Admin and Manager endpoints for Shopee product catalog and warehouse sync.
 * (Mounted under /api/marketplace-connections/shopee).
 */
@RestController
@RequestMapping("/api/marketplace-connections/shopee")
@RequiredArgsConstructor
public class ShopeeCatalogController {

    private final ShopeeProductImportService importService;
    private final ShopeePriceSyncService priceSyncService;

    @PostMapping("/products/import")
    public ApiResponse<ImportReport> importProducts() {
        return ApiResponse.success("Import finished", importService.importForCurrentTenant());
    }

    @GetMapping("/products/import-runs")
    public ApiResponse<List<ImportReport>> importRuns(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(importService.recentRuns(limit));
    }

    @PostMapping("/warehouses/sync")
    public ApiResponse<Map<String, Integer>> syncWarehouses() {
        return ApiResponse.success("Warehouses synced",
                Map.of("warehouses", importService.syncWarehousesForCurrentTenant()));
    }

    @PostMapping("/prices/sync")
    public ApiResponse<ShopeePriceSyncService.Result> syncPrices() {
        return ApiResponse.success("Prices synced", priceSyncService.syncForCurrentTenant());
    }
}
