package com.smartomni.integration.controller;

import com.smartomni.integration.scheduler.OrderPollingScheduler;
import com.smartomni.integration.scheduler.PriceSyncScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/jobs")
public class InternalIntegrationJobsController {

    private final PriceSyncScheduler priceSyncScheduler;
    private final OrderPollingScheduler orderPollingScheduler;

    @PostMapping("/sync-prices")
    public ResponseEntity<Void> syncPrices() {
        priceSyncScheduler.syncPricesForAllTenants();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/poll-orders")
    public ResponseEntity<Map<String, Object>> pollOrders() {
        int synced = orderPollingScheduler.pollOrdersFromMarketplaces();
        return ResponseEntity.ok(Map.of("status", "success", "syncedOrders", synced));
    }
}
