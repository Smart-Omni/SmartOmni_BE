package com.smartomni.integration.service.catalog;

import com.smartomni.integration.client.shopee.ShopeeApiException;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ItemListResult;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeItemDetail;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeModelDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.PriceConfig;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Price sync for Shopee (UC-43/FR-092).
 * Reads tenant price configs, fetches current prices from Shopee, and applies changes via {@link PriceChangePolicy}.
 */
@Slf4j
@Service
public class ShopeePriceSyncService {

    public record Result(int considered, int applied, int unchanged, int heldAnomaly, int rejected, int missing, String fetchError) {
    }

    private final ShopeeTenantConnections tenantConnections;
    private final ShopeeCatalogClient catalog;
    private final CatalogMappingStore store;
    private final PriceChangePolicy policy;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ShopeePriceSyncService(ShopeeTenantConnections tenantConnections,
                                  ShopeeCatalogClient catalog,
                                  CatalogMappingStore store,
                                  PriceChangePolicy policy,
                                  TransactionTemplate tx,
                                  @Qualifier("integrationBusinessClock") Clock clock) {
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

    public Result sync(MarketplaceConnection connection) {
        long connectionId = connection.getId();
        List<LinkRef> links = tx.execute(s -> store.listLinks(connectionId));
        List<PriceConfig> configs = tx.execute(s -> store.priceConfigs());
        Instant now = clock.instant();

        List<LinkRef> due = links.stream().filter(l -> isDue(l, configs, now)).toList();
        if (due.isEmpty()) {
            return new Result(0, 0, 0, 0, 0, 0, null);
        }

        Map<String, PriceEntry> platformPrices = new HashMap<>();
        String fetchError = null;

        try {
            int offset = 0;
            while (true) {
                ItemListResult page = catalog.getItemList(connectionId, offset, 50, null);
                if (page.itemIds().isEmpty()) {
                    break;
                }
                List<ShopeeItemDetail> items = catalog.getItemBaseInfo(connectionId, page.itemIds());
                for (ShopeeItemDetail item : items) {
                    if (item.hasModel()) {
                        List<ShopeeModelDetail> models = catalog.getModelList(connectionId, item.itemId());
                        for (ShopeeModelDetail m : models) {
                            platformPrices.put(String.valueOf(m.modelId()),
                                    new PriceEntry(m.currentPrice(), m.currency()));
                        }
                    } else {
                        platformPrices.put(String.valueOf(item.itemId()),
                                new PriceEntry(item.currentPrice(), item.currency()));
                    }
                }
                if (!page.hasNextPage() || page.nextOffset() <= offset) {
                    break;
                }
                offset = page.nextOffset();
            }
        } catch (Exception e) {
            fetchError = e instanceof ShopeeApiException s ? s.summary() : e.getClass().getSimpleName();
            log.warn("Shopee price fetch incomplete for connection={}: {}", connectionId, fetchError);
        }

        int applied = 0;
        int unchanged = 0;
        int held = 0;
        int rejected = 0;
        int missing = 0;

        for (LinkRef link : due) {
            PriceEntry entry = platformPrices.get(link.externalSkuId());
            if (entry == null) {
                missing++;
                continue;
            }

            try {
                PriceChangePolicy.Result res = tx.execute(s -> policy.apply(link, entry.price(), entry.currency()));
                switch (res) {
                    case APPLIED -> applied++;
                    case UNCHANGED -> unchanged++;
                    case HELD_ANOMALY -> held++;
                    case INVALID_PRICE, CURRENCY_MISMATCH -> rejected++;
                }
            } catch (Exception e) {
                log.warn("Price sync failed on link {}: {}", link.id(), e.getMessage());
                rejected++;
            }
        }

        return new Result(due.size(), applied, unchanged, held, rejected, missing, fetchError);
    }

    private static boolean isDue(LinkRef link, List<PriceConfig> configs, Instant now) {
        PriceConfig cfg = findConfig(link, configs);
        if (cfg == null || !cfg.enabled()) {
            return false;
        }
        if (link.lastSyncedAt() == null) {
            return true;
        }
        int freqMinutes = cfg.frequencyMinutes() != null && cfg.frequencyMinutes() > 0 ? cfg.frequencyMinutes() : 15;
        return Duration.between(link.lastSyncedAt(), now).toMinutes() >= freqMinutes;
    }

    private static PriceConfig findConfig(LinkRef link, List<PriceConfig> configs) {
        for (PriceConfig c : configs) {
            if ("sku".equalsIgnoreCase(c.scope()) && Objects.equals(c.skuId(), link.skuId())) {
                return c;
            }
        }
        for (PriceConfig c : configs) {
            if ("tenant".equalsIgnoreCase(c.scope())
                    && (c.platform() == null || "shopee".equalsIgnoreCase(c.platform()))) {
                return c;
            }
        }
        return null;
    }

    private record PriceEntry(BigDecimal price, String currency) {
    }
}
