package com.smartomni.integration.service.catalog;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCatalogMappingStore implements CatalogMappingStore {

    private static final String LINK_COLUMNS = "l.id, l.sku_id, s.product_id, l.connection_id, l.external_sku_id, l.external_product_id, "
            + "l.seller_sku, l.platform_price, l.currency, l.last_synced_at";
    private static final String LINK_FROM = " FROM product_marketplace_links l JOIN product_skus s ON s.id = l.sku_id AND s.tenant_id = l.tenant_id ";

    private static final RowMapper<LinkRef> LINK = (rs, i) -> new LinkRef(rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("product_id"),
            rs.getLong("connection_id"), rs.getString("external_sku_id"), rs.getString("external_product_id"), rs.getString("seller_sku"),
            rs.getBigDecimal("platform_price"), rs.getString("currency"), instant(rs.getTimestamp("last_synced_at")));

    private final JdbcTemplate jdbc;

    public JdbcCatalogMappingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long tenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required", HttpStatus.FORBIDDEN, "TENANT_CONTEXT_REQUIRED");
        }
        return tenantId;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }

    @Override
    public Optional<LinkRef> findLinkByExternalSku(long connectionId, String externalSkuId) {
        return jdbc.query("SELECT " + LINK_COLUMNS + LINK_FROM + "WHERE l.tenant_id = ? AND l.connection_id = ? AND l.external_sku_id = ?",
                LINK, tenant(), connectionId, externalSkuId).stream().findFirst();
    }

    @Override
    public Optional<LinkRef> findLinkBySku(long skuId, long connectionId) {
        return jdbc.query("SELECT " + LINK_COLUMNS + LINK_FROM + "WHERE l.tenant_id = ? AND l.connection_id = ? AND l.sku_id = ?",
                LINK, tenant(), connectionId, skuId).stream().findFirst();
    }

    @Override
    public Optional<SkuRef> findSkuByCode(String skuCode) {
        return jdbc.query("SELECT id, product_id, sku_code FROM product_skus WHERE tenant_id = ? AND sku_code = ?",
                (rs, i) -> new SkuRef(rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku_code")), tenant(), skuCode)
                .stream().findFirst();
    }

    @Override
    public List<LinkRef> listLinks(long connectionId) {
        return jdbc.query("SELECT " + LINK_COLUMNS + LINK_FROM
                        + "WHERE l.tenant_id = ? AND l.connection_id = ? AND l.external_sku_id IS NOT NULL ORDER BY l.id",
                LINK, tenant(), connectionId);
    }

    @Override
    public long countSkus() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM product_skus WHERE tenant_id = ?", Long.class, tenant());
        return count == null ? 0 : count;
    }

    @Override
    public Integer skuLimit() {
        List<Integer> rows = jdbc.query("SELECT p.max_skus FROM tenants t JOIN subscription_plans p ON p.id = t.plan_id WHERE t.id = ?",
                (rs, i) -> (Integer) rs.getObject(1), tenant());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insertReturningId(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("insert did not return an id");
        }
        return key.longValue();
    }

    @Override
    public long createProduct(String name, String description, boolean hidden) {
        return insertReturningId("INSERT INTO products (tenant_id, name, description, status, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?::product_status, ?, now())",
                tenant(), name, description, hidden ? "hidden" : "active", TenantContext.getCurrentUserId());
    }

    @Override
    public void updateProduct(long productId, String name, String description, boolean hidden) {
        jdbc.update("UPDATE products SET name = ?, description = ?, status = ?::product_status, updated_at = now() WHERE tenant_id = ? AND id = ?",
                name, description, hidden ? "hidden" : "active", tenant(), productId);
    }

    @Override
    public void setProductHidden(long productId, boolean hidden) {
        jdbc.update("UPDATE products SET status = ?::product_status, updated_at = now() WHERE tenant_id = ? AND id = ?",
                hidden ? "hidden" : "active", tenant(), productId);
    }

    @Override
    public long createSku(long productId, String skuCode, String variantName, BigDecimal basePrice) {
        return insertReturningId("INSERT INTO product_skus (tenant_id, product_id, sku_code, variant_name, base_price, created_at) "
                + "VALUES (?, ?, ?, ?, ?, now())", tenant(), productId, skuCode, variantName, basePrice);
    }

    @Override
    public long insertLink(long skuId, long connectionId, String externalProductId, String externalSkuId, String sellerSku,
                           String currency, String externalStatus, String productUrl, BigDecimal price) {
        return insertReturningId("INSERT INTO product_marketplace_links (tenant_id, sku_id, connection_id, platform, platform_item_id, "
                        + "platform_product_url, platform_price, last_synced_at, external_product_id, external_sku_id, seller_sku, currency, "
                        + "external_status, created_at) VALUES (?, ?, ?, 'tiktok_shop'::platform_type, ?, ?, ?, now(), ?, ?, ?, ?, ?, now())",
                tenant(), skuId, connectionId, externalProductId, productUrl, price, externalProductId, externalSkuId, sellerSku, currency, externalStatus);
    }

    @Override
    public void updateLinkMeta(long linkId, String externalProductId, String sellerSku, String currency, String externalStatus, String productUrl) {
        jdbc.update("UPDATE product_marketplace_links SET external_product_id = ?, seller_sku = ?, currency = COALESCE(?, currency), "
                        + "external_status = ?, platform_product_url = COALESCE(?, platform_product_url), updated_at = now() WHERE tenant_id = ? AND id = ?",
                externalProductId, sellerSku, currency, externalStatus, productUrl, tenant(), linkId);
    }

    @Override
    public void updateLinkPrice(long linkId, BigDecimal price, String currency, Instant at) {
        jdbc.update("UPDATE product_marketplace_links SET platform_price = ?, currency = COALESCE(?, currency), last_synced_at = ?, updated_at = now() "
                + "WHERE tenant_id = ? AND id = ?", price, currency, ts(at), tenant(), linkId);
    }

    @Override
    public void touchLink(long linkId, Instant at) {
        jdbc.update("UPDATE product_marketplace_links SET last_synced_at = ?, updated_at = now() WHERE tenant_id = ? AND id = ?", ts(at), tenant(), linkId);
    }

    @Override
    public List<PriceConfig> priceConfigs() {
        return jdbc.query("SELECT scope::text AS scope, sku_id, platform::text AS platform, COALESCE(sync_enabled, false) AS enabled, sync_frequency_minutes "
                        + "FROM price_sync_configs WHERE tenant_id = ?",
                (rs, i) -> new PriceConfig(rs.getString("scope"), (Long) rs.getObject("sku_id"), rs.getString("platform"),
                        rs.getBoolean("enabled"), (Integer) rs.getObject("sync_frequency_minutes")), tenant());
    }

    @Override
    public void insertPriceHistory(long skuId, long connectionId, String externalSkuId, BigDecimal oldPrice, BigDecimal newPrice,
                                   String currency, boolean anomaly, boolean applied) {
        jdbc.update("INSERT INTO price_sync_history (tenant_id, sku_id, platform, old_price, new_price, is_anomaly, changed_at, created_at, "
                        + "connection_id, external_sku_id, currency, applied) VALUES (?, ?, 'tiktok_shop'::platform_type, ?, ?, ?, now(), now(), ?, ?, ?, ?)",
                tenant(), skuId, oldPrice, newPrice, anomaly, connectionId, externalSkuId, currency, applied);
    }

    @Override
    public boolean hasPendingAnomaly(long skuId, BigDecimal newPrice) {
        List<Boolean> rows = jdbc.query("SELECT (is_anomaly AND NOT applied AND confirmed_by IS NULL AND new_price = ?) FROM price_sync_history "
                        + "WHERE tenant_id = ? AND sku_id = ? AND platform = 'tiktok_shop'::platform_type ORDER BY changed_at DESC, id DESC LIMIT 1",
                (rs, i) -> rs.getBoolean(1), newPrice, tenant(), skuId);
        return !rows.isEmpty() && rows.get(0);
    }

    @Override
    public void upsertWarehouse(long connectionId, WarehouseRow row) {
        jdbc.update("INSERT INTO marketplace_warehouses (tenant_id, connection_id, external_warehouse_id, name, warehouse_type, sub_type, effect_status, "
                        + "is_default, city, region_code, synced_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now()) "
                        + "ON CONFLICT (connection_id, external_warehouse_id) DO UPDATE SET name = EXCLUDED.name, warehouse_type = EXCLUDED.warehouse_type, "
                        + "sub_type = EXCLUDED.sub_type, effect_status = EXCLUDED.effect_status, is_default = EXCLUDED.is_default, city = EXCLUDED.city, "
                        + "region_code = EXCLUDED.region_code, synced_at = now(), updated_at = now()",
                tenant(), connectionId, row.externalId(), row.name(), row.type(), row.subType(), row.effectStatus(), row.isDefault(), row.city(), row.regionCode());
    }

    @Override
    public Optional<Long> openRun(long connectionId, int staleAfterMinutes) {
        long tenantId = tenant();
        jdbc.update("UPDATE marketplace_import_runs SET status = 'failed', finished_at = now(), updated_at = now(), "
                        + "error_message = 'Run abandoned (no completion recorded)' WHERE tenant_id = ? AND connection_id = ? AND status = 'running' "
                        + "AND started_at < now() - make_interval(mins => ?)", tenantId, connectionId, staleAfterMinutes);
        List<Long> ids = jdbc.query("INSERT INTO marketplace_import_runs (tenant_id, connection_id, status) VALUES (?, ?, 'running') "
                        + "ON CONFLICT (connection_id) WHERE status = 'running' DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong(1), tenantId, connectionId);
        return ids.stream().findFirst();
    }

    @Override
    public void finishRun(long runId, String status, RunCounters c, Integer skuLimit, boolean skuLimitReached, String errorMessage, String itemsJson) {
        jdbc.update("UPDATE marketplace_import_runs SET status = ?, finished_at = now(), updated_at = now(), pages = ?, products_seen = ?, products_created = ?, "
                        + "products_updated = ?, products_skipped = ?, products_failed = ?, skus_created = ?, skus_linked = ?, skus_conflicted = ?, "
                        + "skus_unmatched = ?, sku_limit = ?, sku_limit_reached = ?, error_message = ?, items = ?::jsonb WHERE tenant_id = ? AND id = ?",
                status, c.pages(), c.productsSeen(), c.productsCreated(), c.productsUpdated(), c.productsSkipped(), c.productsFailed(), c.skusCreated(),
                c.skusLinked(), c.skusConflicted(), c.skusUnmatched(), skuLimit, skuLimitReached, errorMessage, itemsJson, tenant(), runId);
    }

    @Override
    public List<RunRow> recentRuns(long connectionId, int limit) {
        return jdbc.query("SELECT id, status, started_at, finished_at, pages, products_seen, products_created, products_updated, products_skipped, "
                        + "products_failed, skus_created, skus_linked, skus_conflicted, skus_unmatched, sku_limit, sku_limit_reached, error_message, items::text AS items "
                        + "FROM marketplace_import_runs WHERE tenant_id = ? AND connection_id = ? ORDER BY started_at DESC, id DESC LIMIT ?",
                (rs, i) -> new RunRow(rs.getLong("id"), rs.getString("status"), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                        new RunCounters(rs.getInt("pages"), rs.getInt("products_seen"), rs.getInt("products_created"), rs.getInt("products_updated"),
                                rs.getInt("products_skipped"), rs.getInt("products_failed"), rs.getInt("skus_created"), rs.getInt("skus_linked"),
                                rs.getInt("skus_conflicted"), rs.getInt("skus_unmatched")),
                        (Integer) rs.getObject("sku_limit"), rs.getBoolean("sku_limit_reached"), rs.getString("error_message"), rs.getString("items")),
                tenant(), connectionId, limit);
    }
}
