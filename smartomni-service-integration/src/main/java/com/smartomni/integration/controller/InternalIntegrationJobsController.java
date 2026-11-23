package com.smartomni.integration.controller;

import com.smartomni.integration.scheduler.CatalogSyncScheduler;
import com.smartomni.integration.scheduler.OrderPollingScheduler;
import com.smartomni.integration.scheduler.PriceSyncScheduler;
import com.smartomni.integration.service.catalog.MarketplaceStockPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/jobs")
public class InternalIntegrationJobsController {

    private final PriceSyncScheduler priceSyncScheduler;
    private final OrderPollingScheduler orderPollingScheduler;
    private final CatalogSyncScheduler catalogSyncScheduler;
    private final MarketplaceStockPushService stockPushService;

    public record StockPushRequest(Long tenantId, String platform, Long skuId, int quantity) {}

    @PostMapping("/push-stock")
    public ResponseEntity<Map<String, Object>> pushStock(@RequestBody StockPushRequest req) {
        try (var scope = com.smartomni.common.tenant.TenantContext.openScope(req.tenantId(), "admin", null)) {
            stockPushService.pushStock(req.tenantId(), req.platform(), req.skuId(), req.quantity());
        }
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @PostMapping("/sync-prices")
    public ResponseEntity<Void> syncPrices() {
        priceSyncScheduler.syncPricesForAllTenants();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sync-catalog")
    public ResponseEntity<Map<String, Object>> syncCatalog() {
        catalogSyncScheduler.syncCatalogForAllTenants();
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @PostMapping("/poll-orders")
    public ResponseEntity<Map<String, Object>> pollOrders() {
        int synced = orderPollingScheduler.pollOrdersFromMarketplaces();
        return ResponseEntity.ok(Map.of("status", "success", "syncedOrders", synced));
    }
}
