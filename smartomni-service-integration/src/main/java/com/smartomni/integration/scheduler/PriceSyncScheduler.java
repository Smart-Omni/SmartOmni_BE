package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.catalog.TiktokPriceSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UC-43: He thong tu dong fetch & cap nhat gia san pham theo lich.
 * FR-090 (goi API lay gia theo lich), FR-091 (luu gia rieng theo kenh),
 * FR-092 (canh bao chenh lech bat thuong), FR-093 (log lich su thay doi gia).
 *
 * <p>TikTok Shop: delegates to {@link TiktokPriceSyncService}, which reads {@code price_sync_configs} and the
 * (connection, platform SKU id) links, so only SKUs whose sync is enabled and due are touched. The anomaly
 * threshold is {@code smartomni.price-sync.anomaly-threshold-percent}. Shopee has no price source yet and is skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceSyncScheduler {

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokPriceSyncService tiktokPriceSync;
    private final TransactionTemplate transactionTemplate;

    public void syncPricesForAllTenants() {
        log.debug("Bat dau chu ky dong bo gia tu dong (UC-43)");

        java.util.List<MarketplaceConnection> connections;
        try (var scope = TenantContext.openScope(null, "price_sync_worker", null)) {
            connections = transactionTemplate.execute(status -> connectionRepository
                    .findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED));
        }
        for (MarketplaceConnection connection : connections) {
            if (connection.getPlatform() != MarketplaceConnection.Platform.TIKTOK_SHOP) {
                continue;
            }
            try (var scope = TenantContext.openScope(connection.getTenantId(), "manager", null)) {
                TiktokPriceSyncService.Result result = tiktokPriceSync.sync(connection);
                log.info("TikTok price sync tenant={} connection={}: {}", connection.getTenantId(), connection.getId(), result);
            } catch (Exception ex) {
                // FR-090 (A1): giu gia cu; lan chay ke tiep se thu lai
                log.warn("Dong bo gia that bai cho tenant={}, platform={}: {}",
                        connection.getTenantId(), connection.getPlatform(), ex.getClass().getSimpleName());
            }
        }
    }
}
