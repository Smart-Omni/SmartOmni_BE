package com.smartomni.integration.service.catalog;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Inventory;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;
import com.smartomni.integration.dto.tiktok.TiktokCatalogItemDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for TikTok Shop catalog browse and push operations (stock & price updates).
 */
@Slf4j
@Service
public class TiktokCatalogService {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokCatalogClient catalogClient;
    private final CatalogMappingStore mappingStore;
    private final TransactionTemplate transactionTemplate;

    public TiktokCatalogService(TiktokTenantConnections tenantConnections, TiktokCatalogClient catalogClient) {
        this(tenantConnections, catalogClient, null, null);
    }

    @Autowired
    public TiktokCatalogService(TiktokTenantConnections tenantConnections,
                                TiktokCatalogClient catalogClient,
                                CatalogMappingStore mappingStore,
                                TransactionTemplate transactionTemplate) {
        this.tenantConnections = tenantConnections;
        this.catalogClient = catalogClient;
        this.mappingStore = mappingStore;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Lists products and SKUs from TikTok Simulator and enriches each SKU with its
     * internal SmartOmni SKU mapping for the current tenant (D8).
     */
    public List<TiktokCatalogItemDto> listCatalogForCurrentTenant() {
        MarketplaceConnection connection = tenantConnections.requireConnected();

        List<Product> products = new ArrayList<>();
        catalogClient.forEachProductPage(connection.getId(), page -> {
            if (page != null && page.products() != null) {
                products.addAll(page.products());
            }
        });

        List<Product> detailedProducts = new ArrayList<>(products.size());
        for (Product product : products) {
            boolean needsDetail = product.skus().isEmpty()
                    || product.skus().stream().anyMatch(sku -> sku.price() == null);
            if (needsDetail && product.id() != null && !product.id().isBlank()) {
                try {
                    Product detail = catalogClient.getProduct(connection.getId(), product.id());
                    if (detail != null) {
                        detailedProducts.add(detail);
                        continue;
                    }
                } catch (Exception ex) {
                    log.warn("Failed to fetch TikTok product detail for productId={}: {}", product.id(), ex.getMessage());
                }
            }
            detailedProducts.add(product);
        }

        List<CatalogMappingStore.MappedSkuView> mappedSkus = loadMappedSkus(connection);
        Map<String, CatalogMappingStore.MappedSkuView> byExternalSkuId = new HashMap<>();
        Map<String, CatalogMappingStore.MappedSkuView> bySellerSku = new HashMap<>();
        for (CatalogMappingStore.MappedSkuView view : mappedSkus) {
            if (view.externalSkuId() != null && !view.externalSkuId().isBlank()) {
                byExternalSkuId.putIfAbsent(view.externalSkuId(), view);
            }
            if (view.sellerSku() != null && !view.sellerSku().isBlank()) {
                bySellerSku.putIfAbsent(view.sellerSku(), view);
            }
        }

        List<TiktokCatalogItemDto> items = new ArrayList<>();
        for (Product product : detailedProducts) {
            for (Sku sku : product.skus()) {
                CatalogMappingStore.MappedSkuView mapped = null;
                if (sku.id() != null) {
                    mapped = byExternalSkuId.get(sku.id());
                }
                if (mapped == null && sku.sellerSku() != null) {
                    mapped = bySellerSku.get(sku.sellerSku());
                }

                BigDecimal currentPrice = null;
                String currency = null;
                if (sku.price() != null) {
                    currentPrice = TiktokCatalogDtos.parseAmount(sku.price().salePrice());
                    if (currentPrice == null) {
                        currentPrice = TiktokCatalogDtos.parseAmount(sku.price().taxExclusivePrice());
                    }
                    currency = sku.price().currency();
                }

                int totalStock = 0;
                for (Inventory inv : sku.inventory()) {
                    if (inv.quantity() != null) {
                        totalStock += inv.quantity();
                    }
                }

                items.add(TiktokCatalogItemDto.builder()
                        .tiktokProductId(product.id())
                        .tiktokSkuId(sku.id())
                        .sellerSku(sku.sellerSku())
                        .productName(product.title())
                        .currentPrice(currentPrice)
                        .currency(currency)
                        .stock(totalStock)
                        .localProductId(mapped != null ? mapped.productId() : null)
                        .localSkuId(mapped != null ? mapped.skuId() : null)
                        .localSkuCode(mapped != null ? mapped.skuCode() : null)
                        .mapped(mapped != null)
                        .build());
            }
        }
        return items;
    }

    private List<CatalogMappingStore.MappedSkuView> loadMappedSkus(MarketplaceConnection connection) {
        if (mappingStore == null) {
            return List.of();
        }
        if (transactionTemplate != null) {
            try (var scope = TenantContext.getTenantId() == null
                    ? TenantContext.openScope(connection.getTenantId(), null, null)
                    : null) {
                return transactionTemplate.execute(status -> mappingStore.listMappedSkus(connection.getId()));
            }
        }
        return mappingStore.listMappedSkus(connection.getId());
    }

    /**
     * Updates product stock on TikTok Shop for the authenticated tenant.
     */
    public void updateStock(String shopId, String productId, String skuId, Integer quantity) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Updating TikTok stock: tenantId={}, connectionId={}, productId={}, skuId={}, qty={}",
                connection.getTenantId(), connection.getId(), productId, skuId, quantity);
        catalogClient.updateStock(connection.getId(), productId, skuId, quantity);
    }

    /**
     * Updates product price on TikTok Shop for the authenticated tenant.
     */
    public void updatePrice(String shopId, String productId, String skuId, BigDecimal originalPrice, BigDecimal salePrice) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        log.info("Updating TikTok price: tenantId={}, connectionId={}, productId={}, skuId={}, origPrice={}, salePrice={}",
                connection.getTenantId(), connection.getId(), productId, skuId, originalPrice, salePrice);
        catalogClient.updatePrice(connection.getId(), productId, skuId, originalPrice, salePrice,
                linkCurrency(connection, skuId));
    }

    /** Currency stored on the SKU link, or {@code null} (the client then sends VND) when the SKU is not linked. */
    private String linkCurrency(MarketplaceConnection connection, String skuId) {
        if (mappingStore == null || transactionTemplate == null || skuId == null || skuId.isBlank()) {
            return null;
        }
        try (var scope = TenantContext.getTenantId() == null
                ? TenantContext.openScope(connection.getTenantId(), null, null)
                : null) {
            return transactionTemplate.execute(status -> mappingStore.findLinkByExternalSku(connection.getId(), skuId)
                    .map(CatalogMappingStore.LinkRef::currency)
                    .orElse(null));
        }
    }
}

