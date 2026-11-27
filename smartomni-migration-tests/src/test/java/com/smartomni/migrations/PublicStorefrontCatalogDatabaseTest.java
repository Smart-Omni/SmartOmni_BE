package com.smartomni.migrations;

import com.smartomni.catalog.controller.PublicStorefrontController;
import com.smartomni.catalog.dto.PublicStorefrontProductDto;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL + RLS evidence for the public storefront catalog: the store comes from the path even when the Host or a JWT
 * set another tenant, only on-sale listings of connected shops are shown, and nothing internal leaks.
 */
class PublicStorefrontCatalogDatabaseTest {

    private static final String PASSWORD = "temporary-test-password";
    private static EmbeddedPostgres postgres;
    private static String url;
    private static ConfigurableApplicationContext context;
    private static PublicStorefrontController controller;

    @BeforeAll
    static void start() throws Exception {
        Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            execute(c, "CREATE ROLE smartomni_migrator LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
            execute(c, "CREATE ROLE smartomni_app LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        }
        String name = "store_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            execute(c, "CREATE DATABASE " + name);
        }
        url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + name;
        try (Connection c = admin()) {
            execute(c, "ALTER SCHEMA public OWNER TO smartomni_migrator");
            execute(c, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            execute(c, "GRANT USAGE ON SCHEMA public TO smartomni_app");
        }
        Flyway.configure().dataSource(url, "smartomni_migrator", PASSWORD).defaultSchema("public")
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        try (Connection c = admin()) {
            execute(c, "INSERT INTO tenants (name, subdomain, status) VALUES "
                    + "('Shop A','shop-a','active'),('Shop B','shop-b','active'),('Shop C','shop-c','locked')");
            execute(c, "INSERT INTO marketplace_connections (tenant_id, platform, shop_id, shop_name, status) VALUES "
                    + "(1,'tiktok_shop','TT-A','A tiktok','connected'),"
                    + "(1,'shopee','SP-A','A shopee','connected'),"
                    + "(2,'tiktok_shop','TT-B','B tiktok','connected'),"
                    + "(3,'tiktok_shop','TT-C','C tiktok','connected'),"
                    + "(2,'shopee','SP-B-OLD','B old shop','disconnected')");
        }
        context = new SpringApplicationBuilder(PublicStorefrontCatalogTestApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=smartomni_app",
                "--spring.datasource.password=" + PASSWORD,
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.open-in-view=false",
                "--spring.sql.init.mode=never",
                "--smartomni.tiktok.product-url-template=https://tiktok.example/product/{productId}",
                "--logging.level.root=WARN");
        controller = context.getBean(PublicStorefrontController.class);
    }

    @AfterAll
    static void stop() throws Exception {
        TenantContext.clear();
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = admin()) {
            execute(c, "DELETE FROM product_marketplace_links");
            execute(c, "DELETE FROM inventory");
            execute(c, "DELETE FROM product_images");
            execute(c, "DELETE FROM product_skus");
            execute(c, "DELETE FROM products");
        }
        // Shop A: a TikTok + Shopee product, plus products that must stay hidden for different reasons.
        long shirt = product(1, "Ao thun A", "active");
        long shirtS = sku(1, shirt, "A-SHIRT-S", "Đen / S", "100000");
        long shirtM = sku(1, shirt, "A-SHIRT-M", "Đen / M", "110000");
        link(1, shirtS, 1, "TT-ITEM-1", "ACTIVATE", "159000", null, "2026-11-01");
        link(1, shirtS, 2, "SP-ITEM-1", "NORMAL", "169000", "https://shopee-simulator.onrender.com/product/SP-ITEM-1", "2026-11-01");
        link(1, shirtM, 1, "TT-ITEM-1", "ACTIVATE", "167000", null, "2026-11-01");
        stock(1, shirtS, 5, 0);
        stock(1, shirtM, 2, 2); // all reserved -> sold out
        image(1, shirt, "javascript:alert(1)", 0);
        image(1, shirt, "https://img.example/shirt.png", 1);

        long hidden = product(1, "Ẩn đi", "hidden");
        link(1, sku(1, hidden, "A-HIDDEN", "x", "1"), 1, "TT-H", "ACTIVATE", "1000", null, "2026-11-01");
        long delisted = product(1, "Ngừng bán", "active");
        link(1, sku(1, delisted, "A-DEL", "x", "1"), 1, "TT-D", "SELLER_DEACTIVATED", "1000", null, "2026-11-01");
        long oldShop = product(2, "Shop cũ", "active");
        link(2, sku(2, oldShop, "B-OLD", "x", "1"), 5, "SP-O", "ACTIVATE", "1000", null, "2026-11-01");
        long noPrice = product(1, "Chưa có giá", "active");
        link(1, sku(1, noPrice, "A-NOPRICE", "x", "1"), 1, "TT-N", "ACTIVATE", null, null, "2026-11-01");
        product(1, "Không có link", "active");

        long mug = product(1, "Cốc 100% sứ_trắng", "active");
        long mugSku = sku(1, mug, "A-MUG", null, "50000");
        link(1, mugSku, 1, "TT-MUG", "ACTIVATE", "60000", "https://tiktok.example/product/OLD", "2026-10-01");

        long bag = product(2, "Túi của B", "active");
        link(2, sku(2, bag, "B-BAG", "Nâu", "1"), 3, "TT-B", "ACTIVATE", "90000", null, "2026-11-01");
        long locked = product(3, "Hàng của C", "active");
        link(3, sku(3, locked, "C-1", "x", "1"), 4, "TT-C", "ACTIVATE", "90000", null, "2026-11-01");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void anonymousShopperSeesOnlyProductsOnSaleInConnectedShops() {
        Page<PublicStorefrontProductDto> page = list("shop-a", 0, 20, null, null);

        assertEquals(List.of("Cốc 100% sứ_trắng", "Ao thun A"), names(page));
        assertEquals(2, page.getTotalElements());

        PublicStorefrontProductDto shirt = page.getContent().get(1);
        assertEquals("https://img.example/shirt.png", shirt.getImageUrl(), "unsafe image URLs are skipped");
        assertEquals(2, shirt.getVariants().size());
        PublicStorefrontProductDto.Variant small = shirt.getVariants().get(0);
        assertEquals("Đen / S", small.getVariantName());
        assertEquals(2, small.getOffers().size());
        PublicStorefrontProductDto.Offer tiktok = offer(small, "tiktok_shop");
        assertEquals(0, new BigDecimal("159000").compareTo(tiktok.getPrice()));
        assertEquals("VND", tiktok.getCurrency());
        assertTrue(tiktok.isAvailable());
        assertEquals("https://tiktok.example/product/TT-ITEM-1", tiktok.getUrl());
        assertEquals("https://shopiz-kappa.vercel.app/product/SP-ITEM-1", offer(small, "shopee").getUrl());
        assertFalse(offer(shirt.getVariants().get(1), "tiktok_shop").isAvailable(), "reserved stock is not sellable");
    }

    @Test
    void storeFromPathWinsOverHostOrJwtTenant() {
        // SubdomainContextFilter / a logged-in shopper of shop A already put tenant 1 in the context.
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentRole("CUSTOMER");
        TenantContext.setCurrentSubdomain("shop-a");

        assertEquals(List.of("Túi của B"), names(list("shop-b", 0, 20, null, null)));
        assertEquals(1L, TenantContext.getTenantId(), "the caller's context is restored afterwards");
        assertEquals("CUSTOMER", TenantContext.getCurrentRole());
    }

    @Test
    void superAdminSessionDoesNotWidenThePublicCatalog() {
        TenantContext.setCurrentRole("SUPER_ADMIN");
        assertEquals(List.of("Túi của B"), names(list("shop-b", 0, 20, null, null)));
    }

    @Test
    void unknownLockedOrMalformedStoresAreNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> list("shop-c", 0, 20, null, null));
        assertThrows(ResourceNotFoundException.class, () -> list("no-such-shop", 0, 20, null, null));
        assertThrows(ResourceNotFoundException.class, () -> list("../shop-a", 0, 20, null, null));
    }

    @Test
    void storeWithoutProductsReturnsEmptyPage() throws Exception {
        try (Connection c = admin()) {
            execute(c, "INSERT INTO tenants (name, subdomain, status) VALUES ('Empty','empty-shop','active') ON CONFLICT DO NOTHING");
        }
        Page<PublicStorefrontProductDto> page = list("empty-shop", 0, 20, null, null);
        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    @Test
    void searchTreatsWildcardsLiterallyAndPagingIsCapped() {
        assertEquals(List.of("Cốc 100% sứ_trắng"), names(list("shop-a", 0, 20, "100%", null)));
        assertEquals(List.of("Cốc 100% sứ_trắng"), names(list("shop-a", 0, 20, "sứ_", null)));
        assertTrue(names(list("shop-a", 0, 20, "%%", null)).isEmpty());

        Page<PublicStorefrontProductDto> first = list("shop-a", 0, 1, null, "name");
        assertEquals(List.of("Ao thun A"), names(first));
        assertEquals(2, first.getTotalElements());
        assertEquals(50, list("shop-a", 0, 500, null, null).getSize());
        assertThrows(BusinessException.class, () -> list("shop-a", 0, 20, null, "price"));
    }

    @Test
    void storedProductUrlIsKeptAndVariantNameDefaults() {
        PublicStorefrontProductDto mug = list("shop-a", 0, 20, "Cốc", null).getContent().get(0);
        assertEquals("Standard", mug.getVariants().get(0).getVariantName());
        assertEquals("https://tiktok.example/product/OLD", mug.getVariants().get(0).getOffers().get(0).getUrl());
        assertNull(mug.getImageUrl());
    }

    @Test
    void detailIsScopedToTheStore() throws Exception {
        long shirt = id("SELECT id FROM products WHERE name = 'Ao thun A'");
        long bag = id("SELECT id FROM products WHERE name = 'Túi của B'");
        long hidden = id("SELECT id FROM products WHERE name = 'Ẩn đi'");

        assertEquals("Ao thun A", controller.detail("shop-a", shirt).getData().getName());
        assertThrows(ResourceNotFoundException.class, () -> controller.detail("shop-a", bag));
        assertThrows(ResourceNotFoundException.class, () -> controller.detail("shop-a", hidden));
    }

    // ---- helpers

    private static Page<PublicStorefrontProductDto> list(String store, int page, int size, String search, String sort) {
        return controller.list(store, page, size, search, sort).getData();
    }

    private static List<String> names(Page<PublicStorefrontProductDto> page) {
        return page.getContent().stream().map(PublicStorefrontProductDto::getName).toList();
    }

    private static PublicStorefrontProductDto.Offer offer(PublicStorefrontProductDto.Variant variant, String platform) {
        return variant.getOffers().stream().filter(o -> platform.equals(o.getPlatform())).findFirst().orElseThrow();
    }

    private static long product(long tenant, String name, String status) throws Exception {
        return id("INSERT INTO products (tenant_id, name, description, status) VALUES (" + tenant + ", '" + name
                + "', 'mô tả', '" + status + "') RETURNING id");
    }

    private static long sku(long tenant, long product, String code, String variant, String price) throws Exception {
        return id("INSERT INTO product_skus (tenant_id, product_id, sku_code, variant_name, base_price) VALUES (" + tenant + ", "
                + product + ", '" + code + "', " + (variant == null ? "NULL" : "'" + variant + "'") + ", " + price + ") RETURNING id");
    }

    private static void link(long tenant, long sku, long connection, String item, String status, String price, String productUrl,
                             String syncedAt) throws Exception {
        try (Connection c = admin()) {
            execute(c, "INSERT INTO product_marketplace_links (tenant_id, sku_id, connection_id, platform, platform_item_id, "
                    + "external_status, platform_price, platform_product_url, currency, last_synced_at) VALUES (" + tenant + ", " + sku
                    + ", " + connection + ", (SELECT platform FROM marketplace_connections WHERE id = " + connection + "), '" + item
                    + "', '" + status + "', " + (price == null ? "NULL" : price) + ", "
                    + (productUrl == null ? "NULL" : "'" + productUrl + "'") + ", 'VND', '" + syncedAt + "')");
        }
    }

    private static void stock(long tenant, long sku, int onHand, int reserved) throws Exception {
        try (Connection c = admin()) {
            execute(c, "INSERT INTO inventory (tenant_id, sku_id, quantity_on_hand, reserved_quantity) VALUES ("
                    + tenant + ", " + sku + ", " + onHand + ", " + reserved + ")");
        }
    }

    private static void image(long tenant, long product, String imageUrl, int order) throws Exception {
        try (Connection c = admin()) {
            execute(c, "INSERT INTO product_images (tenant_id, product_id, url, sort_order) VALUES ("
                    + tenant + ", " + product + ", '" + imageUrl + "', " + order + ")");
        }
    }

    private static long id(String sql) throws Exception {
        try (Connection c = admin(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(url, "postgres", "postgres");
    }

    private static void execute(Connection c, String sql) throws Exception {
        try (var s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
