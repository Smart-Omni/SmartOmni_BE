package com.smartomni.integration.service.catalog;

import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Service for TikTok Shop catalog push operations (stock & price updates).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokCatalogService {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokCatalogClient catalogClient;

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
        catalogClient.updatePrice(connection.getId(), productId, skuId, originalPrice, salePrice);
    }
}

