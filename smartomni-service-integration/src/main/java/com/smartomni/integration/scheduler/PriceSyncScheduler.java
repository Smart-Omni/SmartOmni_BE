package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.catalog.ShopeePriceSyncService;
import com.smartomni.integration.service.catalog.TiktokPriceSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * UC-43: He thong tu dong fetch & cap nhat gia san pham theo lich.
 * FR-090 (goi API lay gia theo lich), FR-091 (luu gia rieng theo kenh),
 * FR-092 (canh bao chenh lech bat thuong), FR-093 (log lich su thay doi gia).
 *
 * <p>TikTok Shop: delegates to {@link TiktokPriceSyncService}, which reads {@code price_sync_configs} and the
 * (connection, platform SKU id) links, so only SKUs whose sync is enabled and due are touched. The anomaly
 * threshold is {@code smartomni.price-sync.anomaly-threshold-percent}. Shopee delegates to {@link ShopeePriceSyncService}.
 */
@Slf4j
@Component
public class PriceSyncScheduler {

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokPriceSyncService tiktokPriceSync;
    private final TransactionTemplate transactionTemplate;
    private final ShopeePriceSyncService shopeePriceSync;

    @Autowired
    public PriceSyncScheduler(MarketplaceConnectionRepository connectionRepository,
                              TiktokPriceSyncService tiktokPriceSync,
                              TransactionTemplate transactionTemplate,
                              Optional<ShopeePriceSyncService> shopeePriceSync) {
        this.connectionRepository = connectionRepository;
        this.tiktokPriceSync = tiktokPriceSync;
        this.transactionTemplate = transactionTemplate;
        this.shopeePriceSync = shopeePriceSync.orElse(null);
    }

    public PriceSyncScheduler(MarketplaceConnectionRepository connectionRepository,
                              TiktokPriceSyncService tiktokPriceSync,
                              TransactionTemplate transactionTemplate,
                              ShopeePriceSyncService shopeePriceSync) {
        this(connectionRepository, tiktokPriceSync, transactionTemplate, Optional.ofNullable(shopeePriceSync));
    }

    public void syncPricesForAllTenants() {
        log.debug("Bat dau chu ky dong bo gia tu dong (UC-43)");

        List<MarketplaceConnection> connections;
        try (var scope = TenantContext.openScope(null, "price_sync_worker", null)) {
            connections = transactionTemplate.execute(status -> connectionRepository
                    .findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED));
        }
        for (MarketplaceConnection connection : connections) {
            if (connection.getPlatform() == MarketplaceConnection.Platform.TIKTOK_SHOP) {
                try (var scope = TenantContext.openScope(connection.getTenantId(), "manager", null)) {
                    TiktokPriceSyncService.Result result = tiktokPriceSync.sync(connection);
                    log.info("TikTok price sync tenant={} connection={}: {}", connection.getTenantId(), connection.getId(), result);
                } catch (Exception ex) {
                    // FR-090 (A1): giu gia cu; lan chay ke tiep se thu lai
                    log.warn("Dong bo gia that bai cho tenant={}, platform={}: {}",
                            connection.getTenantId(), connection.getPlatform(), ex.getClass().getSimpleName());
                }
            } else if (connection.getPlatform() == MarketplaceConnection.Platform.SHOPEE && shopeePriceSync != null) {
                try (var scope = TenantContext.openScope(connection.getTenantId(), "manager", null)) {
                    var result = shopeePriceSync.sync(connection);
                    log.info("Shopee price sync tenant={} connection={}: {}", connection.getTenantId(), connection.getId(), result);
                } catch (Exception ex) {
                    log.warn("Dong bo gia that bai cho tenant={}, platform={}: {}",
                            connection.getTenantId(), connection.getPlatform(), ex.getClass().getSimpleName());
                }
            }
        }
    }
}
