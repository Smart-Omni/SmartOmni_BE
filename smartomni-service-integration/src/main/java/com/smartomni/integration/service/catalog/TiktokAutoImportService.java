package com.smartomni.integration.service.catalog;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokAutoImportReportDto;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Inventory;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.SkuRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * WF-01: Idempotent auto-import of TikTok Shop products & SKUs into SmartOmni catalog.
 *
 * <p>For each SKU:
 * <ol>
 *   <li>Already mapped {@code (connectionId, tiktokSkuId)} -> update link snapshot, count as {@code skippedCount}.</li>
 *   <li>Unmapped, but tenant already has {@code product_skus.sku_code == seller_sku} -> link existing SKU, count as {@code linkedExistingCount}.</li>
 *   <li>Neither mapped nor existing -> create Product (once per TikTok product), create Sku (code from {@code seller_sku}
 *       or fallback {@code TT-{tiktokSkuId}}), initialize inventory, create link, count as {@code importedCount}.</li>
 * </ol>
 */
@Slf4j
@Service
public class TiktokAutoImportService {

    private final TiktokTenantConnections tenantConnections;
    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokCatalogClient catalogClient;
    private final CatalogMappingStore store;
    private final TransactionTemplate transactionTemplate;
    private final TiktokProperties properties;

    @Autowired
    public TiktokAutoImportService(TiktokTenantConnections tenantConnections,
                                   MarketplaceConnectionRepository connectionRepository,
                                   TiktokCatalogClient catalogClient,
                                   CatalogMappingStore store,
                                   TransactionTemplate transactionTemplate,
                                   @Autowired(required = false) TiktokProperties properties) {
        this.tenantConnections = tenantConnections;
        this.connectionRepository = connectionRepository;
        this.catalogClient = catalogClient;
        this.store = store;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    /**
     * Manual trigger entry point for the authenticated tenant (POST /api/v1/tiktok/catalog/auto-import).
     */
    public TiktokAutoImportReportDto autoImportForCurrentTenant(String requestedShopId) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        if (requestedShopId != null && !requestedShopId.isBlank()
                && connection.getShopId() != null && !connection.getShopId().isBlank()
                && !connection.getShopId().equals(requestedShopId.trim())) {
            throw new BusinessException("Active TikTok connection does not match shopId: " + requestedShopId,
                    HttpStatus.NOT_FOUND, "TIKTOK_CONNECTION_NOT_FOUND");
        }
        return autoImport(connection);
    }

    /**
     * Background/async entry point triggered after OAuth completion.
     */
    public TiktokAutoImportReportDto autoImportForConnection(Long connectionId) {
        if (connectionId == null) {
            throw new BusinessException("connectionId is required", HttpStatus.BAD_REQUEST, "INVALID_CONNECTION_ID");
        }
        MarketplaceConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new BusinessException("TikTok connection not found: " + connectionId,
                        HttpStatus.NOT_FOUND, "TIKTOK_CONNECTION_NOT_FOUND"));
        return autoImport(connection);
    }

    /**
     * Executes idempotent auto-import for the given connection.
     */
    public TiktokAutoImportReportDto autoImport(MarketplaceConnection connection) {
        if (connection == null || connection.getId() == null || connection.getTenantId() == null) {
            throw new BusinessException("Valid TikTok connection is required", HttpStatus.BAD_REQUEST, "INVALID_CONNECTION");
        }

        long connectionId = connection.getId();
        long tenantId = connection.getTenantId();
        String shopId = connection.getShopId();
        String role = TenantContext.getCurrentRole() != null
                ? TenantContext.getCurrentRole().toLowerCase(Locale.ROOT)
                : "tenant_admin";

        List<Product> summaries = new ArrayList<>();
        catalogClient.forEachProductPage(connectionId, page -> {
            if (page != null && page.products() != null) {
                summaries.addAll(page.products());
            }
        });

        List<Product> detailedProducts = new ArrayList<>(summaries.size());
        for (Product summary : summaries) {
            if (summary == null || summary.id() == null || summary.id().isBlank()) {
                continue;
            }
            Product detail = null;
            try {
                detail = catalogClient.getProduct(connectionId, summary.id());
            } catch (Exception ex) {
                log.warn("Could not fetch TikTok product detail for productId={}, using summary: {}",
                        summary.id(), ex.getMessage());
            }
            detailedProducts.add(detail != null ? detail : summary);
        }

        int totalProducts = detailedProducts.size();
        int totalSkus = 0;
        int importedCount = 0;
        int linkedExistingCount = 0;
        int skippedCount = 0;
        boolean hadErrors = false;

        try (var scope = TenantContext.getTenantId() == null
                ? TenantContext.openScope(tenantId, role, null)
                : null) {
            for (Product product : detailedProducts) {
                List<Sku> skus = product.skus();
                totalSkus += skus.size();
                if (skus.isEmpty()) {
                    continue;
                }
                try {
                    ProductCounts counts = executeInTx(() -> persistProduct(tenantId, role, connectionId, product));
                    if (counts != null) {
                        importedCount += counts.imported();
                        linkedExistingCount += counts.linkedExisting();
                        skippedCount += counts.skipped();
                    }
                } catch (Exception ex) {
                    hadErrors = true;
                    log.error("Failed to auto-import TikTok productId={} for tenantId={}: {}",
                            product.id(), tenantId, ex.getMessage(), ex);
                }
            }
        }

        String status = hadErrors ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        log.info("WF-01 TikTok auto-import finished: tenantId={}, shopId={}, products={}, skus={}, imported={}, linkedExisting={}, skipped={}, status={}",
                tenantId, shopId, totalProducts, totalSkus, importedCount, linkedExistingCount, skippedCount, status);

        return TiktokAutoImportReportDto.builder()
                .shopId(shopId)
                .totalProducts(totalProducts)
                .totalSkus(totalSkus)
                .importedCount(importedCount)
                .linkedExistingCount(linkedExistingCount)
                .skippedCount(skippedCount)
                .status(status)
                .build();
    }

    private ProductCounts persistProduct(long tenantId, String role, long connectionId, Product product) {
        store.applyRlsContext(tenantId, role);

        int imported = 0;
        int linkedExisting = 0;
        int skipped = 0;
        Long createdProductId = null;
        boolean hidden = product.status() != null
                && ("SELLER_DEACTIVATED".equalsIgnoreCase(product.status())
                || "PLATFORM_DEACTIVATED".equalsIgnoreCase(product.status()));
        String productUrl = resolveProductUrl(product.id());
        Instant now = Instant.now();

        for (Sku sku : product.skus()) {
            if (sku.id() == null || sku.id().isBlank()) {
                skipped++;
                continue;
            }
            String externalSkuId = sku.id().trim();
            String sellerSku = (sku.sellerSku() != null && !sku.sellerSku().isBlank())
                    ? sku.sellerSku().trim()
                    : null;
            BigDecimal salePrice = resolvePrice(sku);
            String currency = sku.price() != null ? sku.price().currency() : null;
            Integer sourceStock = sumInventory(sku);

            // Case 1: Already mapped for this (connectionId, tiktokSkuId)
            Optional<LinkRef> existingLink = store.findLinkByExternalSku(connectionId, externalSkuId);
            if (existingLink.isPresent()) {
                LinkRef link = existingLink.get();
                store.updateLinkMeta(link.id(), product.id(),
                        sellerSku != null ? sellerSku : link.sellerSku(),
                        currency, product.status(), productUrl);
                if (salePrice != null) {
                    store.updateLinkPrice(link.id(), salePrice, currency, now);
                } else {
                    store.touchLink(link.id(), now);
                }
                if (sourceStock != null) store.initInventoryIfAbsent(link.skuId(), sourceStock);
                skipped++;
                continue;
            }

            // Case 2: Unmapped, but internal SKU exists matching seller_sku
            if (sellerSku != null) {
                Optional<SkuRef> existingSku = store.findSkuByCode(sellerSku);
                if (existingSku.isPresent()) {
                    SkuRef skuRef = existingSku.get();
                    if (store.findLinkBySku(skuRef.id(), connectionId).isPresent()) {
                        log.warn("Internal SKU '{}' (id={}) is already linked to another TikTok SKU on connectionId={}; skipping externalSkuId={}",
                                sellerSku, skuRef.id(), connectionId, externalSkuId);
                        skipped++;
                    } else {
                        store.insertLink(skuRef.id(), connectionId, product.id(), externalSkuId, sellerSku,
                                currency, product.status(), productUrl, salePrice);
                        if (sourceStock != null) store.initInventoryIfAbsent(skuRef.id(), sourceStock);
                        linkedExisting++;
                    }
                    continue;
                }
            }

            // Case 3: Neither mapping nor internal SKU exists -> create Product (once), Sku, Inventory, Link
            String skuCode = sellerSku != null ? sellerSku : "TT-" + externalSkuId;
            Optional<SkuRef> fallbackSku = store.findSkuByCode(skuCode);
            if (fallbackSku.isPresent()) {
                SkuRef skuRef = fallbackSku.get();
                if (store.findLinkBySku(skuRef.id(), connectionId).isEmpty()) {
                    store.insertLink(skuRef.id(), connectionId, product.id(), externalSkuId, sellerSku,
                            currency, product.status(), productUrl, salePrice);
                    if (sourceStock != null) store.initInventoryIfAbsent(skuRef.id(), sourceStock);
                    linkedExisting++;
                } else {
                    skipped++;
                }
                continue;
            }

            if (createdProductId == null) {
                String productName = (product.title() != null && !product.title().isBlank())
                        ? product.title().trim()
                        : skuCode;
                createdProductId = store.createProduct(productName, product.description(), hidden);
            }

            String variantName = buildVariantName(sku);
            BigDecimal basePrice = salePrice != null ? salePrice : BigDecimal.ZERO;
            long localSkuId = store.createSku(createdProductId, skuCode, variantName, basePrice);
            if (sourceStock != null) store.initInventoryIfAbsent(localSkuId, sourceStock);
            store.insertLink(localSkuId, connectionId, product.id(), externalSkuId, sellerSku,
                    currency, product.status(), productUrl, salePrice);
            imported++;
        }

        return new ProductCounts(imported, linkedExisting, skipped);
    }

    private <T> T executeInTx(java.util.function.Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        return transactionTemplate.execute(status -> action.get());
    }

    private static BigDecimal resolvePrice(Sku sku) {
        if (sku.price() == null) {
            return null;
        }
        BigDecimal parsed = TiktokCatalogDtos.parseAmount(sku.price().salePrice());
        if (parsed == null) {
            parsed = TiktokCatalogDtos.parseAmount(sku.price().taxExclusivePrice());
        }
        return parsed;
    }

    private static Integer sumInventory(Sku sku) {
        if (sku.inventory().isEmpty()) return null;
        long total = 0;
        for (Inventory inv : sku.inventory()) {
            if (inv.quantity() == null || inv.quantity() < 0) return null;
            total += inv.quantity();
            if (total > Integer.MAX_VALUE) return null;
        }
        return (int) total;
    }

    private static String buildVariantName(Sku sku) {
        if (sku.salesAttributes() == null || sku.salesAttributes().isEmpty()) {
            return null;
        }
        String joined = sku.salesAttributes().stream()
                .map(attr -> {
                    if (attr.valueName() != null && !attr.valueName().isBlank()) {
                        return attr.valueName().trim();
                    }
                    return null;
                })
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" / "));
        return joined.isBlank() ? null : joined;
    }

    private String resolveProductUrl(String productId) {
        if (properties == null || properties.getProductUrlTemplate() == null
                || properties.getProductUrlTemplate().isBlank() || productId == null) {
            return null;
        }
        return properties.getProductUrlTemplate().replace("{productId}", productId);
    }

    private record ProductCounts(int imported, int linkedExisting, int skipped) {
    }
}
