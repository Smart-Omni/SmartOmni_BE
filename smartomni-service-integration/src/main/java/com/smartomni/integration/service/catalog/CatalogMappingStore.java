package com.smartomni.integration.service.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence used by the TikTok product import and price sync. Every call is scoped to the tenant in
 * {@code TenantContext} (explicit tenant predicate plus PostgreSQL RLS) and must run inside a transaction that
 * the caller opened after setting that context. Catalog tables are shared with the Catalog service, which is
 * why this is plain SQL and not a second JPA mapping of the same entities.
 */
public interface CatalogMappingStore {

    record LinkRef(long id, long skuId, long productId, long connectionId, String externalSkuId, String externalProductId,
                   String sellerSku, BigDecimal platformPrice, String currency, Instant lastSyncedAt) {
    }

    record SkuRef(long id, long productId, String skuCode) {
    }

    record WarehouseRow(String externalId, String name, String type, String subType, String effectStatus,
                        boolean isDefault, String city, String regionCode) {
    }

    /** One row of price_sync_configs (platform null = any platform). */
    record PriceConfig(String scope, Long skuId, String platform, boolean enabled, Integer frequencyMinutes) {
    }

    record RunCounters(int pages, int productsSeen, int productsCreated, int productsUpdated, int productsSkipped,
                       int productsFailed, int skusCreated, int skusLinked, int skusConflicted, int skusUnmatched) {
    }

    record RunRow(long id, String status, Instant startedAt, Instant finishedAt, RunCounters counters, Integer skuLimit,
                  boolean skuLimitReached, String errorMessage, String itemsJson) {
    }

    // ---- links / catalog

    Optional<LinkRef> findLinkByExternalSku(long connectionId, String externalSkuId);

    Optional<LinkRef> findLinkBySku(long skuId, long connectionId);

    Optional<SkuRef> findSkuByCode(String skuCode);

    List<LinkRef> listLinks(long connectionId);

    long countSkus();

    /** Plan SKU limit of the current tenant, or null when the tenant has no plan / the plan is unlimited. */
    Integer skuLimit();

    long createProduct(String name, String description, boolean hidden);

    void updateProduct(long productId, String name, String description, boolean hidden);

    void setProductHidden(long productId, boolean hidden);

    long createSku(long productId, String skuCode, String variantName, BigDecimal basePrice);

    long insertLink(long skuId, long connectionId, String externalProductId, String externalSkuId, String sellerSku,
                    String currency, String externalStatus, String productUrl, BigDecimal price);

    void updateLinkMeta(long linkId, String externalProductId, String sellerSku, String currency, String externalStatus,
                        String productUrl);

    void updateLinkPrice(long linkId, BigDecimal price, String currency, Instant at);

    void touchLink(long linkId, Instant at);

    // ---- price

    List<PriceConfig> priceConfigs();

    void insertPriceHistory(long skuId, long connectionId, String externalSkuId, BigDecimal oldPrice, BigDecimal newPrice,
                            String currency, boolean anomaly, boolean applied);

    /** True when the latest history row of this SKU is an unconfirmed, unapplied anomaly with the same new price. */
    boolean hasPendingAnomaly(long skuId, BigDecimal newPrice);

    // ---- warehouses

    void upsertWarehouse(long connectionId, WarehouseRow row);

    // ---- import runs

    /** Marks runs left "running" for longer than {@code staleAfterMinutes} as failed, then opens a run. Empty when one is already running. */
    Optional<Long> openRun(long connectionId, int staleAfterMinutes);

    void finishRun(long runId, String status, RunCounters counters, Integer skuLimit, boolean skuLimitReached,
                   String errorMessage, String itemsJson);

    List<RunRow> recentRuns(long connectionId, int limit);
}
