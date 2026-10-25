package com.smartomni.integration.service.catalog;

import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.PriceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UC-43 for TikTok Shop. Reads the tenant's price-sync configuration (a SKU-level config wins over the tenant-level one;
 * no config or {@code sync_enabled = false} means "do not sync"), fetches current SKU prices with Search Products
 * (one paged read for the whole connection), and applies each SKU independently through {@link PriceChangePolicy}.
 * A failed fetch, a missing SKU or an invalid price leaves the stored price as it was.
 */
@Slf4j
@Service
public class TiktokPriceSyncService {

    public record Result(int considered, int applied, int unchanged, int heldAnomaly, int rejected, int missing, String fetchError) {
    }

    private final TiktokTenantConnections tenantConnections;
    private final TiktokCatalogClient catalog;
    private final CatalogMappingStore store;
    private final PriceChangePolicy policy;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TiktokPriceSyncService(TiktokTenantConnections tenantConnections, TiktokCatalogClient catalog, CatalogMappingStore store,
                                  PriceChangePolicy policy, TransactionTemplate tx, @Qualifier("integrationBusinessClock") Clock clock) {
        this.tenantConnections = tenantConnections;
        this.catalog = catalog;
        this.store = store;
        this.policy = policy;
        this.tx = tx;
        this.clock = clock;
    }

    public Result syncForCurrentTenant() {
        return sync(tenantConnections.requireConnected());
    }

    /** Requires a tenant context for the connection's tenant. */
    public Result sync(MarketplaceConnection connection) {
        long connectionId = connection.getId();
        List<LinkRef> links = tx.execute(s -> store.listLinks(connectionId));
        List<PriceConfig> configs = tx.execute(s -> store.priceConfigs());
        Instant now = clock.instant();
        List<LinkRef> due = links.stream().filter(l -> isDue(l, configs, now)).toList();
        if (due.isEmpty()) {
            return new Result(0, 0, 0, 0, 0, 0, null);
        }

        Map<String, Sku> platformSkus = new HashMap<>();
        String fetchError = null;
        try {
            catalog.forEachProductPage(connectionId, page -> page.products().forEach(p -> p.skus().forEach(sku -> {
                if (sku.id() != null) {
                    platformSkus.put(sku.id(), sku);
                }
            })));
        } catch (RuntimeException e) {
            fetchError = e instanceof TiktokApiException t ? t.summary() : e.getClass().getSimpleName();
            log.warn("TikTok price fetch incomplete for connection={}: {}", connectionId, fetchError);
        }

        int applied = 0;
        int unchanged = 0;
        int held = 0;
        int rejected = 0;
        int missing = 0;
        for (LinkRef link : due) {
            Sku sku = platformSkus.get(link.externalSkuId());
            if (sku == null) {
                missing++;
                continue;
            }
            var price = sku.price();
            try {
                PriceChangePolicy.Result result = tx.execute(s -> policy.apply(link,
                        price == null ? null : TiktokCatalogDtos.parseAmount(price.salePrice()), price == null ? null : price.currency()));
                switch (result) {
                    case APPLIED -> applied++;
                    case UNCHANGED -> unchanged++;
                    case HELD_ANOMALY -> held++;
                    case INVALID_PRICE, CURRENCY_MISMATCH -> rejected++;
                }
            } catch (DataAccessException e) {
                rejected++;
                log.warn("Price update failed for link={}: {}", link.id(), e.getClass().getSimpleName());
            }
        }
        return new Result(due.size(), applied, unchanged, held, rejected, missing, fetchError);
    }

    static boolean isDue(LinkRef link, List<PriceConfig> configs, Instant now) {
        PriceConfig config = effective(link.skuId(), configs);
        if (config == null || !config.enabled()) {
            return false;
        }
        Integer minutes = config.frequencyMinutes();
        return minutes == null || minutes <= 0 || link.lastSyncedAt() == null
                || !link.lastSyncedAt().plus(Duration.ofMinutes(minutes)).isAfter(now);
    }

    /** SKU-level config first, then tenant-level; within a level a TikTok-specific config beats an any-platform one. */
    static PriceConfig effective(long skuId, List<PriceConfig> configs) {
        PriceConfig best = null;
        int bestRank = -1;
        for (PriceConfig c : configs) {
            if (c.platform() != null && !"tiktok_shop".equals(c.platform())) {
                continue;
            }
            boolean skuLevel = "product".equals(c.scope());
            if (skuLevel && (c.skuId() == null || c.skuId() != skuId)) {
                continue;
            }
            int rank = (skuLevel ? 2 : 0) + (c.platform() != null ? 1 : 0);
            if (rank > bestRank) {
                best = c;
                bestRank = rank;
            }
        }
        return best;
    }
}
