package com.smartomni.integration.service.tiktok;

import com.smartomni.integration.service.catalog.CatalogMappingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class TiktokStockLinkRepository {
    private final JdbcTemplate jdbc;
    private final CatalogMappingStore mappings;

    public record StockLink(long connectionId, String productId, String skuId) { }

    @Transactional(readOnly = true)
    public List<StockLink> findConnected(long tenantId, long skuId) {
        mappings.applyRlsContext(tenantId, "tenant_admin");
        return jdbc.query("""
                SELECT l.connection_id, l.external_product_id, l.external_sku_id
                FROM product_marketplace_links l
                JOIN marketplace_connections c ON c.id = l.connection_id AND c.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.sku_id = ? AND c.platform = 'tiktok_shop'
                  AND c.status = 'connected' AND l.external_product_id IS NOT NULL
                  AND l.external_sku_id IS NOT NULL
                ORDER BY l.connection_id
                """, (rs, row) -> new StockLink(rs.getLong(1), rs.getString(2), rs.getString(3)), tenantId, skuId);
    }
}
