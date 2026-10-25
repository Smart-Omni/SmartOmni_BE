package com.smartomni.integration.service.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.MarketplaceProductDto;
import com.smartomni.integration.dto.MarketplaceProductDto.VariantDto;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Warehouse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.RunCounters;
import com.smartomni.integration.service.catalog.CatalogMappingStore.SkuRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UC-41/FR-082..FR-087 for TikTok Shop: idempotent, page-token driven import of the connection's products.
 *
 * <p>Mapping key is (connection, platform SKU id). Internal SKUs are matched by seller SKU = {@code sku_code} within
 * the tenant. Per variant: already linked -> updated; seller SKU of an unlinked internal SKU -> linked; no internal
 * SKU -> created (counts against the plan limit); blank seller SKU, a seller SKU shared by several variants of
 * the product, or a seller SKU whose internal SKU is linked to another platform SKU of the same connection ->
 * reported, never guessed. The SKU limit stops the run explicitly (status {@code stopped_sku_limit}).
 * A product is persisted in its own transaction and no transaction is open while the platform is called.
 */
@Slf4j
@Service
public class TiktokProductImportService {

    static final Set<String> ACTIVE = Set.of("ACTIVATE");
    static final Set<String> DEACTIVATED = Set.of("SELLER_DEACTIVATED", "PLATFORM_DEACTIVATED");
    private static final int MAX_ITEMS = 500;
    private static final int MAX_CONSECUTIVE_API_FAILURES = 5;
    private static final int STALE_RUN_MINUTES = 30;

    private final TiktokTenantConnections tenantConnections;
    private final TiktokCatalogClient catalog;
    private final CatalogMappingStore store;
    private final PriceChangePolicy priceChanges;
    private final TransactionTemplate tx;
    private final TiktokProperties properties;
    private final ObjectMapper mapper;

    public TiktokProductImportService(TiktokTenantConnections tenantConnections, TiktokCatalogClient catalog, CatalogMappingStore store,
                                      PriceChangePolicy priceChanges, TransactionTemplate tx, TiktokProperties properties, ObjectMapper mapper) {
        this.tenantConnections = tenantConnections;
        this.catalog = catalog;
        this.store = store;
        this.priceChanges = priceChanges;
        this.tx = tx;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** Admin/Manager entry point: imports for the caller tenant's connection. */
    public ImportReport importForCurrentTenant() {
        return importProducts(tenantConnections.requireConnected());
    }

    /** Warehouse list sync for the caller tenant. */
    public int syncWarehousesForCurrentTenant() {
        return syncWarehouses(tenantConnections.requireConnected());
    }

    public List<ImportReport> recentRuns(int limit) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        return tx.execute(s -> store.recentRuns(connection.getId(), Math.max(1, Math.min(limit, 50)))).stream()
                .map(this::toReport).toList();
    }

    public int syncWarehouses(MarketplaceConnection connection) {
        List<Warehouse> warehouses = catalog.listWarehouses(connection.getId());
        tx.executeWithoutResult(s -> {
            for (Warehouse w : warehouses) {
                if (w.id() == null || w.id().isBlank()) {
                    continue;
                }
                store.upsertWarehouse(connection.getId(), new CatalogMappingStore.WarehouseRow(w.id(), w.name(), w.type(), w.subType(),
                        w.effectStatus(), Boolean.TRUE.equals(w.isDefault()), w.address() == null ? null : w.address().city(),
                        w.address() == null ? null : w.address().regionCode()));
            }
        });
        return warehouses.size();
    }

    public ImportReport importProducts(MarketplaceConnection connection) {
        long connectionId = connection.getId();
        Long runId = tx.execute(s -> store.openRun(connectionId, STALE_RUN_MINUTES)).orElse(null);
        if (runId == null) {
            throw new BusinessException("A product import is already running for this connection", HttpStatus.CONFLICT, "IMPORT_ALREADY_RUNNING");
        }
        Run run = new Run();
        run.skuLimit = tx.execute(s -> store.skuLimit());
        String status;
        String error = null;
        try {
            try {
                syncWarehouses(connection);
            } catch (RuntimeException e) {
                run.addItem(null, null, "WAREHOUSE_SYNC_FAILED", safe(e));
            }
            run.pages = catalog.forEachProductPage(connectionId, page -> {
                run.pages++;
                for (Product summary : page.products()) {
                    processProduct(connection, summary, run);
                }
            });
            status = run.counters().productsFailed() + run.skusConflicted + run.skusUnmatched > 0 ? "completed_with_errors" : "completed";
        } catch (SkuLimitReached stop) {
            status = "stopped_sku_limit";
            error = "Plan SKU limit reached (" + run.skuLimit + "); import stopped. Upgrade the plan or remove SKUs, then run the import again.";
        } catch (TooManyApiFailures stop) {
            status = "failed";
            error = "Stopped after " + MAX_CONSECUTIVE_API_FAILURES + " consecutive platform failures: " + stop.getMessage();
        } catch (RuntimeException e) {
            status = "failed";
            error = safe(e);
            log.warn("TikTok product import failed for connection={}: {}", connectionId, error);
        }
        // pages: forEachProductPage returns the delivered count when it completes; otherwise run.pages holds the partial count.
        RunCounters counters = run.counters();
        String itemsJson = json(run.items);
        String finalStatus = status;
        String finalError = error;
        tx.executeWithoutResult(s -> store.finishRun(runId, finalStatus, counters, run.skuLimit, run.limitReached, finalError, itemsJson));
        return new ImportReport(runId, finalStatus, null, null, counters, run.skuLimit, run.limitReached, finalError, List.copyOf(run.items), run.itemsOmitted);
    }

    private void processProduct(MarketplaceConnection connection, Product summary, Run run) {
        run.productsSeen++;
        String status = summary.status();
        boolean importable = status != null && (ACTIVE.contains(status) || DEACTIVATED.contains(status));
        if (!importable) {
            run.productsSkipped++;
            run.addItem(summary.id(), null, "SKIPPED_STATUS", "Platform status " + status + " is not imported");
            try {
                tx.executeWithoutResult(s -> markExistingLinks(connection.getId(), summary, status));
            } catch (DataAccessException e) {
                run.productsFailed++;
                run.addItem(summary.id(), null, "PERSIST_FAILED", safe(e));
            }
            return;
        }
        Product detail;
        try {
            detail = catalog.getProduct(connection.getId(), summary.id());
            run.consecutiveApiFailures = 0;
        } catch (TiktokApiException e) {
            run.productsFailed++;
            run.addItem(summary.id(), null, "DETAIL_FAILED", e.summary());
            if (++run.consecutiveApiFailures >= MAX_CONSECUTIVE_API_FAILURES) {
                throw new TooManyApiFailures(e.summary());
            }
            return;
        }
        MarketplaceProductDto dto = TiktokProductMapper.toNeutral(detail, status);
        try {
            Run delta = tx.execute(s -> persist(connection, dto, run.skuLimit));
            run.merge(delta);
        } catch (SkuLimitReached stop) {
            run.limitReached = true;
            run.addItem(summary.id(), null, "SKU_LIMIT", stop.getMessage());
            throw stop;
        } catch (DataAccessException e) {
            // e.g. a concurrent writer hit the unique (connection, external sku) index: nothing of this product was kept.
            run.productsFailed++;
            run.addItem(summary.id(), null, "PERSIST_FAILED", safe(e));
        }
    }

    private void markExistingLinks(long connectionId, Product summary, String status) {
        for (var sku : summary.skus()) {
            if (sku.id() == null) {
                continue;
            }
            store.findLinkByExternalSku(connectionId, sku.id()).ifPresent(link -> {
                store.updateLinkMeta(link.id(), summary.id(), link.sellerSku(), null, status, null);
                store.setProductHidden(link.productId(), true);
            });
        }
    }

    private enum Action { UPDATE, LINK_EXISTING, CREATE, UNMATCHED, CONFLICT }

    private record Plan(VariantDto variant, Action action, LinkRef link, SkuRef sku, String reason) {
    }

    private Run persist(MarketplaceConnection connection, MarketplaceProductDto dto, Integer skuLimit) {
        Run run = new Run();
        long connectionId = connection.getId();
        boolean hidden = DEACTIVATED.contains(dto.getPlatformStatus());
        Map<String, Integer> sellerSkuCounts = new HashMap<>();
        for (VariantDto v : dto.getVariants()) {
            if (v.getSellerSku() != null) {
                sellerSkuCounts.merge(v.getSellerSku(), 1, Integer::sum);
            }
        }
        List<Plan> plans = new ArrayList<>();
        for (VariantDto v : dto.getVariants()) {
            plans.add(plan(connectionId, v, sellerSkuCounts));
        }
        int newSkus = (int) plans.stream().filter(p -> p.action() == Action.CREATE).count();
        if (newSkus > 0 && skuLimit != null) {
            long existing = store.countSkus();
            if (existing + newSkus > skuLimit) {
                throw new SkuLimitReached("Creating " + newSkus + " SKU(s) would exceed the plan limit of " + skuLimit + " (currently " + existing + ")");
            }
        }
        // Everything below only writes after all checks passed for this product.
        Long productId = plans.stream().filter(p -> p.link() != null).map(p -> p.link().productId()).findFirst()
                .or(() -> plans.stream().filter(p -> p.sku() != null).map(p -> p.sku().productId()).findFirst()).orElse(null);
        boolean productCreated = false;
        int changes = 0;
        for (Plan plan : plans) {
            VariantDto v = plan.variant();
            switch (plan.action()) {
                case UNMATCHED -> {
                    run.skusUnmatched++;
                    run.addItem(dto.getPlatformItemId(), v.getExternalSkuId(), "SKU_UNMATCHED", plan.reason());
                }
                case CONFLICT -> {
                    run.skusConflicted++;
                    run.addItem(dto.getPlatformItemId(), v.getExternalSkuId(), "SKU_CONFLICT", plan.reason());
                }
                case UPDATE -> {
                    store.updateLinkMeta(plan.link().id(), dto.getPlatformItemId(), v.getSellerSku() != null ? v.getSellerSku() : plan.link().sellerSku(),
                            v.getCurrency(), dto.getPlatformStatus(), productUrl(dto.getPlatformItemId()));
                    reportPrice(dto, v, priceChanges.apply(plan.link(), v.getPrice(), v.getCurrency()), run);
                    changes++;
                }
                case LINK_EXISTING -> {
                    store.insertLink(plan.sku().id(), connectionId, dto.getPlatformItemId(), v.getExternalSkuId(), v.getSellerSku(), v.getCurrency(),
                            dto.getPlatformStatus(), productUrl(dto.getPlatformItemId()), v.getPrice());
                    run.skusLinked++;
                    changes++;
                }
                case CREATE -> {
                    if (productId == null) {
                        productId = store.createProduct(dto.getName() == null || dto.getName().isBlank() ? v.getSellerSku() : dto.getName(),
                                dto.getDescription(), hidden);
                        productCreated = true;
                    }
                    long skuId = store.createSku(productId, v.getSellerSku(), v.getVariantName(), v.getPrice());
                    store.insertLink(skuId, connectionId, dto.getPlatformItemId(), v.getExternalSkuId(), v.getSellerSku(), v.getCurrency(),
                            dto.getPlatformStatus(), productUrl(dto.getPlatformItemId()), v.getPrice());
                    run.skusCreated++;
                    changes++;
                }
            }
        }
        if (productCreated) {
            run.productsCreated++;
        } else if (changes > 0) {
            run.productsUpdated++;
        } else {
            run.productsSkipped++;
        }
        return run;
    }

    private Plan plan(long connectionId, VariantDto v, Map<String, Integer> sellerSkuCounts) {
        if (v.getExternalSkuId() == null || v.getExternalSkuId().isBlank()) {
            return new Plan(v, Action.UNMATCHED, null, null, "Variant has no platform SKU id");
        }
        var existingLink = store.findLinkByExternalSku(connectionId, v.getExternalSkuId());
        if (existingLink.isPresent()) {
            return new Plan(v, Action.UPDATE, existingLink.get(), null, null);
        }
        if (v.getSellerSku() == null) {
            return new Plan(v, Action.UNMATCHED, null, null, "Variant has no seller SKU, so it cannot be matched to a SmartOmni SKU");
        }
        if (sellerSkuCounts.getOrDefault(v.getSellerSku(), 0) > 1) {
            return new Plan(v, Action.CONFLICT, null, null, "Seller SKU '" + v.getSellerSku() + "' is used by several variants of this product");
        }
        var sku = store.findSkuByCode(v.getSellerSku());
        if (sku.isEmpty()) {
            return new Plan(v, Action.CREATE, null, null, null);
        }
        var linkedElsewhere = store.findLinkBySku(sku.get().id(), connectionId);
        if (linkedElsewhere.isPresent()) {
            return new Plan(v, Action.CONFLICT, null, null, "Seller SKU '" + v.getSellerSku()
                    + "' is already linked to another platform SKU of this connection");
        }
        return new Plan(v, Action.LINK_EXISTING, null, sku.get(), null);
    }

    private void reportPrice(MarketplaceProductDto dto, VariantDto v, PriceChangePolicy.Result result, Run run) {
        if (result == PriceChangePolicy.Result.HELD_ANOMALY || result == PriceChangePolicy.Result.INVALID_PRICE
                || result == PriceChangePolicy.Result.CURRENCY_MISMATCH) {
            run.addItem(dto.getPlatformItemId(), v.getExternalSkuId(), "PRICE_" + result.name(), "Stored price was not changed");
        }
    }

    private String productUrl(String productId) {
        String template = properties.getProductUrlTemplate();
        return template == null || template.isBlank() || productId == null ? null : template.replace("{productId}", productId);
    }

    private ImportReport toReport(CatalogMappingStore.RunRow row) {
        List<ImportReport.Item> items = new ArrayList<>();
        try {
            for (var node : mapper.readTree(row.itemsJson())) {
                items.add(new ImportReport.Item(text(node, "externalProductId"), text(node, "externalSkuId"), text(node, "outcome"), text(node, "message")));
            }
        } catch (JsonProcessingException e) {
            // keep the report usable; the raw column is still in the database
        }
        return new ImportReport(row.id(), row.status(), row.startedAt(), row.finishedAt(), row.counters(), row.skuLimit(), row.skuLimitReached(),
                row.errorMessage(), items, 0);
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private String json(List<ImportReport.Item> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /** Never includes URLs or payloads. */
    private static String safe(RuntimeException e) {
        if (e instanceof TiktokApiException t) {
            return t.summary();
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message.lines().findFirst().orElse(""));
    }

    private static final class SkuLimitReached extends RuntimeException {
        SkuLimitReached(String message) {
            super(message, null, false, false);
        }
    }

    private static final class TooManyApiFailures extends RuntimeException {
        TooManyApiFailures(String message) {
            super(message, null, false, false);
        }
    }

    /** Mutable per-run state. */
    private static final class Run {
        int pages;
        int productsSeen;
        int productsCreated;
        int productsUpdated;
        int productsSkipped;
        int productsFailed;
        int skusCreated;
        int skusLinked;
        int skusConflicted;
        int skusUnmatched;
        Integer skuLimit;
        boolean limitReached;
        int consecutiveApiFailures;
        int itemsOmitted;
        final List<ImportReport.Item> items = new ArrayList<>();

        void addItem(String productId, String skuId, String outcome, String message) {
            if (items.size() >= MAX_ITEMS) {
                itemsOmitted++;
                return;
            }
            items.add(new ImportReport.Item(productId, skuId, outcome, message));
        }

        void merge(Run other) {
            productsCreated += other.productsCreated;
            productsUpdated += other.productsUpdated;
            productsSkipped += other.productsSkipped;
            skusCreated += other.skusCreated;
            skusLinked += other.skusLinked;
            skusConflicted += other.skusConflicted;
            skusUnmatched += other.skusUnmatched;
            other.items.forEach(i -> addItem(i.externalProductId(), i.externalSkuId(), i.outcome(), i.message()));
        }

        RunCounters counters() {
            return new RunCounters(pages, productsSeen, productsCreated, productsUpdated, productsSkipped, productsFailed, skusCreated,
                    skusLinked, skusConflicted, skusUnmatched);
        }
    }
}
