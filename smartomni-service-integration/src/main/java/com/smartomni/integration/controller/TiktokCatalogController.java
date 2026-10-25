package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.service.catalog.ImportReport;
import com.smartomni.integration.service.catalog.TiktokPriceSyncService;
import com.smartomni.integration.service.catalog.TiktokProductImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin/Manager triggers for the TikTok catalog sync. The connection is always the caller tenant's own; no tenant or connection id is accepted. */
@RestController
@RequestMapping("/api/marketplace-connections/tiktok-shop")
@RequiredArgsConstructor
public class TiktokCatalogController {

    private final TiktokProductImportService importService;
    private final TiktokPriceSyncService priceSyncService;

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
}
