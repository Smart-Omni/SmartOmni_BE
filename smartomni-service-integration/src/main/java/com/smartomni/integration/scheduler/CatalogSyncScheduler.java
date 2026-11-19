package com.smartomni.integration.scheduler;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.catalog.ImportReport;
import com.smartomni.integration.service.catalog.ShopeeProductImportService;
import com.smartomni.integration.service.catalog.TiktokProductImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Background scheduler for periodic automatic catalog synchronization across connected marketplaces (UC-41).
 * Runs every 30 minutes with an initial 2-minute delay to allow services to start up cleanly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogSyncScheduler {

    private final MarketplaceConnectionRepository connectionRepository;
    private final ShopeeProductImportService shopeeImportService;
    private final TiktokProductImportService tiktokImportService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${smartomni.catalog-sync.fixed-delay-ms:1800000}", initialDelayString = "${smartomni.catalog-sync.initial-delay-ms:120000}")
    public void syncCatalogScheduled() {
        syncCatalogForAllTenants();
    }

    public void syncCatalogForAllTenants() {
        log.info("Starting background marketplace catalog synchronization cycle...");

        List<MarketplaceConnection> connections;
        try (var scope = TenantContext.openScope(null, "price_sync_worker", null)) {
            connections = transactionTemplate.execute(status ->
                    connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED));
        }

        if (connections == null || connections.isEmpty()) {
            log.debug("No active marketplace connections found for catalog sync.");
            return;
        }

        for (MarketplaceConnection connection : connections) {
            Long tenantId = connection.getTenantId();
            if (tenantId == null) continue;

            try (var scope = TenantContext.openScope(tenantId, "admin", null)) {
                if (connection.getPlatform() == MarketplaceConnection.Platform.SHOPEE) {
                    log.info("Syncing Shopee catalog for tenant={} connection={}", tenantId, connection.getId());
                    ImportReport report = shopeeImportService.importProducts(connection);
                    log.info("Shopee catalog sync completed for tenant={}: status={}, counters={}",
                            tenantId, report.status(), report.counters());
                } else if (connection.getPlatform() == MarketplaceConnection.Platform.TIKTOK_SHOP) {
                    log.info("Syncing TikTok catalog for tenant={} connection={}", tenantId, connection.getId());
                    ImportReport report = tiktokImportService.importProducts(connection);
                    log.info("TikTok catalog sync completed for tenant={}: status={}, counters={}",
                            tenantId, report.status(), report.counters());
                }
            } catch (BusinessException be) {
                if ("IMPORT_ALREADY_RUNNING".equals(be.getErrorCode())) {
                    log.debug("Catalog sync skipped for tenant={} connection={}: import already running", tenantId, connection.getId());
                } else {
                    log.warn("Catalog sync business error for tenant={} connection={}: {}", tenantId, connection.getId(), be.getMessage());
                }
            } catch (Exception ex) {
                log.error("Catalog sync failed for tenant={} connection={}: {}", tenantId, connection.getId(), ex.getMessage(), ex);
            }
        }
        log.info("Finished marketplace catalog synchronization cycle.");
    }
}
