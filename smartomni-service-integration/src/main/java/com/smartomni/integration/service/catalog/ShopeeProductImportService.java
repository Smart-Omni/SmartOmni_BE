package com.smartomni.integration.service.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.shopee.ShopeeApiException;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ItemListResult;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeItemDetail;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeModelDetail;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeWarehouseDetail;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.dto.MarketplaceProductDto;
import com.smartomni.integration.dto.MarketplaceProductDto.VariantDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.RunCounters;
import com.smartomni.integration.service.catalog.CatalogMappingStore.SkuRef;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Idempotent, paginated import of products from Shopee into SmartOmni catalog.
 * Supports single products and 2-tier variation models.
 */
@Slf4j
@Service
public class ShopeeProductImportService {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_ITEMS = 500;
    private static final int STALE_RUN_MINUTES = 30;

    private final ShopeeTenantConnections tenantConnections;
    private final ShopeeCatalogClient catalog;
    private final CatalogMappingStore store;
    private final PriceChangePolicy priceChanges;
    private final TransactionTemplate tx;
    private final ShopeeProperties properties;
    private final ObjectMapper mapper;

    public ShopeeProductImportService(ShopeeTenantConnections tenantConnections,
                                      ShopeeCatalogClient catalog,
                                      CatalogMappingStore store,
                                      PriceChangePolicy priceChanges,
                                      TransactionTemplate tx,
                                      ShopeeProperties properties,
                                      ObjectMapper mapper) {
        this.tenantConnections = tenantConnections;
        this.catalog = catalog;
        this.store = store;
        this.priceChanges = priceChanges;
        this.tx = tx;
        this.properties = properties;
        this.mapper = mapper;
    }

    public ImportReport importForCurrentTenant() {
        return importProducts(tenantConnections.requireConnected());
    }

    public int syncWarehousesForCurrentTenant() {
        return syncWarehouses(tenantConnections.requireConnected());
    }

    public List<ImportReport> recentRuns(int limit) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        return tx.execute(s -> store.recentRuns(connection.getId(), Math.max(1, Math.min(limit, 50)))).stream()
                .map(this::toReport).toList();
    }

    public int syncWarehouses(MarketplaceConnection connection) {
        List<ShopeeWarehouseDetail> warehouses = catalog.listWarehouses(connection.getId());
        tx.executeWithoutResult(s -> {
            for (ShopeeWarehouseDetail w : warehouses) {
                if (w.warehouseId() == null || w.warehouseId().isBlank()) {
                    continue;
                }
                store.upsertWarehouse(connection.getId(), new CatalogMappingStore.WarehouseRow(
                        w.warehouseId(), w.name(), "NORMAL", "SELLER_WAREHOUSE", "ACTIVE",
                        false, w.city(), w.region()
                ));
            }
        });
        return warehouses.size();
    }

    public ImportReport importProducts(MarketplaceConnection connection) {
        long connectionId = connection.getId();
        Long runId = tx.execute(s -> store.openRun(connectionId, STALE_RUN_MINUTES).orElse(null));
        if (runId == null) {
            throw new BusinessException("A product import is already running for this Shopee connection",
                    HttpStatus.CONFLICT, "IMPORT_ALREADY_RUNNING");
        }

        Run run = new Run();
        String status = "completed";
        String failureReason = null;
        int offset = 0;

        try {
            while (true) {
                ItemListResult page = catalog.getItemList(connectionId, offset, PAGE_SIZE, null);
                run.pages++;

                List<Long> itemIds = page.itemIds();
                if (itemIds.isEmpty()) {
                    break;
                }

                List<ShopeeItemDetail> items = catalog.getItemBaseInfo(connectionId, itemIds);
                for (ShopeeItemDetail item : items) {
                    run.productsSeen++;
                    List<ShopeeModelDetail> models = item.hasModel()
                            ? catalog.getModelList(connectionId, item.itemId())
                            : List.of();

                    MarketplaceProductDto dto = ShopeeProductMapper.toDto(item, models);
                    persistProduct(connectionId, dto, run);

                    if (run.skuLimitReached) {
                        status = "stopped_sku_limit";
                        break;
                    }
                }

                if (run.skuLimitReached || !page.hasNextPage() || page.nextOffset() <= offset) {
                    break;
                }
                offset = page.nextOffset();
            }
        } catch (Exception e) {
            log.error("Shopee import failed on connection {}", connectionId, e);
            status = "failed";
            failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        if (status.equals("completed") && (!run.items.isEmpty() || run.productsFailed > 0 || run.skusUnmatched > 0)) {
            status = "completed_with_errors";
        }

        final String finalStatus = status;
        final String finalFailureReason = failureReason;
        tx.executeWithoutResult(s -> store.finishRun(runId, finalStatus, run.counters(), run.skuLimit,
                run.skuLimitReached, finalFailureReason, json(run.items)));

        return new ImportReport(runId, finalStatus, null, null, run.counters(), run.skuLimit, run.skuLimitReached,
                finalFailureReason, run.items, run.itemsOmitted);
    }

    private void persistProduct(long connectionId, MarketplaceProductDto dto, Run run) {
        tx.executeWithoutResult(s -> {
            Integer limit = store.skuLimit();
            run.skuLimit = limit;
            long skusCount = store.countSkus();

            Map<String, Integer> skuCounts = countSellerSkus(dto.getVariants());
            List<Plan> plans = new ArrayList<>();
            int willCreate = 0;

            for (VariantDto v : dto.getVariants()) {
                Plan plan = plan(connectionId, v, skuCounts);
                plans.add(plan);
                if (plan.action == Action.CREATE) willCreate++;
            }

            if (limit != null && skusCount + willCreate > limit) {
                run.skuLimitReached = true;
                run.addItem(dto.getPlatformItemId(), null, "SKU_LIMIT_REACHED",
                        "Import stopped: tenant reached plan limit of " + limit + " SKUs");
                return;
            }

            Long productId = null;
            boolean productCreated = false;
            int changes = 0;

            for (Plan plan : plans) {
                VariantDto v = plan.variant;
                switch (plan.action) {
                    case UNMATCHED -> {
                        run.skusUnmatched++;
                        run.addItem(dto.getPlatformItemId(), v.getExternalSkuId(), "SKU_UNMATCHED", plan.reason);
                    }
                    case CONFLICT -> {
                        run.skusConflicted++;
                        run.addItem(dto.getPlatformItemId(), v.getExternalSkuId(), "SKU_CONFLICT", plan.reason);
                    }
                    case UPDATE -> {
                        store.updateLinkMeta(plan.link.id(), dto.getPlatformItemId(),
                                v.getSellerSku() != null ? v.getSellerSku() : plan.link.sellerSku(),
                                v.getCurrency(), dto.getPlatformStatus(), null);
                        reportPrice(dto, v, priceChanges.apply(plan.link, v.getPrice(), v.getCurrency()), run);
                        changes++;
                    }
                    case LINK_EXISTING -> {
                        store.insertLink(plan.sku.id(), connectionId, dto.getPlatformItemId(), v.getExternalSkuId(),
                                v.getSellerSku(), v.getCurrency(), dto.getPlatformStatus(), null, v.getPrice());
                        run.skusLinked++;
                        changes++;
                    }
                    case CREATE -> {
                        if (productId == null) {
                            productId = store.createProduct(dto.getName() != null && !dto.getName().isBlank()
                                    ? dto.getName() : v.getSellerSku(), dto.getDescription(), false);
                            productCreated = true;
                        }
                        long skuId = store.createSku(productId, v.getSellerSku(), v.getVariantName(), v.getPrice());
                        store.insertLink(skuId, connectionId, dto.getPlatformItemId(), v.getExternalSkuId(),
                                v.getSellerSku(), v.getCurrency(), dto.getPlatformStatus(), null, v.getPrice());
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
        });
    }

    private Plan plan(long connectionId, VariantDto v, Map<String, Integer> sellerSkuCounts) {
        if (v.getExternalSkuId() == null || v.getExternalSkuId().isBlank()) {
            return new Plan(v, Action.UNMATCHED, null, null, "Variant has no platform SKU id");
        }
        var existingLink = store.findLinkByExternalSku(connectionId, v.getExternalSkuId());
        if (existingLink.isPresent()) {
            return new Plan(v, Action.UPDATE, existingLink.get(), null, null);
        }
        if (v.getSellerSku() == null || v.getSellerSku().isBlank()) {
            return new Plan(v, Action.UNMATCHED, null, null, "Item/Model has no seller SKU to match internal catalog");
        }
        if (sellerSkuCounts.getOrDefault(v.getSellerSku(), 0) > 1) {
            return new Plan(v, Action.CONFLICT, null, null, "Seller SKU '" + v.getSellerSku() + "' is shared by multiple variants");
        }
        var sku = store.findSkuByCode(v.getSellerSku());
        if (sku.isEmpty()) {
            return new Plan(v, Action.CREATE, null, null, null);
        }
        var linkedElsewhere = store.findLinkBySku(sku.get().id(), connectionId);
        if (linkedElsewhere.isPresent()) {
            return new Plan(v, Action.CONFLICT, null, null, "Seller SKU '" + v.getSellerSku() + "' is already linked to another item");
        }
        return new Plan(v, Action.LINK_EXISTING, null, sku.get(), null);
    }

    private static Map<String, Integer> countSellerSkus(List<VariantDto> variants) {
        Map<String, Integer> counts = new HashMap<>();
        for (VariantDto v : variants) {
            if (v.getSellerSku() != null && !v.getSellerSku().isBlank()) {
                counts.merge(v.getSellerSku(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private void reportPrice(MarketplaceProductDto dto, VariantDto v, PriceChangePolicy.Result result, Run run) {
        if (result == PriceChangePolicy.Result.HELD_ANOMALY || result == PriceChangePolicy.Result.INVALID_PRICE
                || result == PriceChangePolicy.Result.CURRENCY_MISMATCH) {
            run.addItem(dto.getPlatformItemId(), v.getExternalSkuId(), "PRICE_" + result.name(), "Price not updated: " + result.name());
        }
    }

    private ImportReport toReport(CatalogMappingStore.RunRow row) {
        List<ImportReport.Item> items = new ArrayList<>();
        try {
            for (var node : mapper.readTree(row.itemsJson())) {
                items.add(new ImportReport.Item(
                        node.path("externalProductId").asText(null),
                        node.path("externalSkuId").asText(null),
                        node.path("outcome").asText(null),
                        node.path("message").asText(null)
                ));
            }
        } catch (JsonProcessingException ignored) {
        }
        return new ImportReport(row.id(), row.status(), row.startedAt(), row.finishedAt(),
                row.counters(), row.skuLimit(), row.skuLimitReached(), row.errorMessage(), items, 0);
    }

    private String json(List<ImportReport.Item> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private enum Action { UPDATE, LINK_EXISTING, CREATE, UNMATCHED, CONFLICT }

    private record Plan(VariantDto variant, Action action, LinkRef link, SkuRef sku, String reason) {
    }

    private static class Run {
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
        boolean skuLimitReached;
        final List<ImportReport.Item> items = new ArrayList<>();
        int itemsOmitted;

        void addItem(String extProdId, String extSkuId, String outcome, String msg) {
            if (items.size() < MAX_ITEMS) {
                items.add(new ImportReport.Item(extProdId, extSkuId, outcome, msg));
            } else {
                itemsOmitted++;
            }
        }

        RunCounters counters() {
            return new RunCounters(pages, productsSeen, productsCreated, productsUpdated, productsSkipped,
                    productsFailed, skusCreated, skusLinked, skusConflicted, skusUnmatched);
        }
    }
}
