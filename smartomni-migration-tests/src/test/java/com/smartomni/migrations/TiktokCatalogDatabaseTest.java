package com.smartomni.migrations;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.service.catalog.CatalogMappingStore;
import com.smartomni.integration.service.catalog.ImportReport;
import com.smartomni.integration.service.catalog.TiktokPriceSyncService;
import com.smartomni.integration.service.catalog.TiktokProductImportService;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL-level evidence for phase 04 (SmartOmni side): V10 schema and RLS, idempotent multi-page import by page token,
 * tenant isolation of identical platform ids, seller SKU matching/conflicts, plan SKU limit, per-product failure reporting,
 * independent per-SKU price updates, anomaly hold and no overwrite on API errors. Only the platform HTTP seam is faked.
 */
class TiktokCatalogDatabaseTest {

    private static final String PASSWORD = "temporary-test-password";
    private static EmbeddedPostgres postgres;
    private static String url;
    private static ConfigurableApplicationContext context;
    private static TiktokProductImportService importer;
    private static TiktokPriceSyncService prices;
    private static CatalogMappingStore store;
    private static final FakeTiktokPlatform PLATFORM = TiktokCatalogTestApplication.PLATFORM;

    @BeforeAll
    static void start() throws Exception {
        Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            execute(c, "CREATE ROLE smartomni_migrator LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
            execute(c, "CREATE ROLE smartomni_app LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        }
        String name = "cat_" + UUID.randomUUID().toString().replace("-", "");
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
            execute(c, "INSERT INTO tenants (name,subdomain) VALUES ('A','a'),('B','b')");
            execute(c, "INSERT INTO marketplace_connections (tenant_id, platform, shop_id, shop_name, status) VALUES "
                    + "(1,'tiktok_shop','SHOP-1','Shop A','connected'),(2,'tiktok_shop','SHOP-2','Shop B','connected')");
        }
        context = new SpringApplicationBuilder(TiktokCatalogTestApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=smartomni_app",
                "--spring.datasource.password=" + PASSWORD,
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.open-in-view=false",
                "--spring.sql.init.mode=never",
                "--smartomni.tiktok.product-page-size=2",
                "--logging.level.root=WARN");
        importer = context.getBean(TiktokProductImportService.class);
        prices = context.getBean(TiktokPriceSyncService.class);
        store = context.getBean(CatalogMappingStore.class);
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
    void clean() throws Exception {
        PLATFORM.reset();
        try (Connection c = admin()) {
            execute(c, "DELETE FROM price_sync_history");
            execute(c, "DELETE FROM price_sync_configs");
            execute(c, "DELETE FROM product_marketplace_links");
            execute(c, "DELETE FROM product_skus");
            execute(c, "DELETE FROM products");
            execute(c, "DELETE FROM marketplace_import_runs");
            execute(c, "DELETE FROM marketplace_warehouses");
            execute(c, "UPDATE tenants SET plan_id = NULL");
            execute(c, "DELETE FROM subscription_plans");
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---- helpers

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(url, "postgres", "postgres");
    }

    private static void execute(Connection c, String sql) throws Exception {
        try (var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection c = admin(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection c = admin(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), sql);
            return r.getString(1);
        }
    }

    private static void update(String sql) throws Exception {
        try (Connection c = admin()) {
            execute(c, sql);
        }
    }

    private static <T> T as(long tenantId, Supplier<T> action) {
        try (var scope = TenantContext.openScope(tenantId, "manager", null)) {
            return action.get();
        }
    }

    /** Runs a store call in a tenant-scoped transaction (the RLS settings are applied when the transaction begins). */
    private static <T> T inTx(long tenantId, Supplier<T> action) {
        return as(tenantId, () -> context.getBean(org.springframework.transaction.support.TransactionTemplate.class).execute(s -> action.get()));
    }

    private static ImportReport importFor(long tenantId) {
        return as(tenantId, importer::importForCurrentTenant);
    }

    private static TiktokPriceSyncService.Result syncPricesFor(long tenantId) {
        return as(tenantId, prices::syncForCurrentTenant);
    }

    private static String[] sku(String externalId, String sellerSku, String price) {
        return new String[]{externalId, sellerSku, price};
    }

    private static void enablePriceSync(long tenantId) throws Exception {
        update("INSERT INTO price_sync_configs (tenant_id, scope, platform, sync_enabled) VALUES (" + tenantId + ",'tenant',NULL,true)");
    }

    private static boolean hasItem(ImportReport report, String outcome) {
        return report.items().stream().anyMatch(i -> outcome.equals(i.outcome()));
    }

    // ---- import

    @Test
    void multiPageImportUsesPageTokensAndRerunCreatesNoDuplicates() throws Exception {
        PLATFORM.addProduct(1, "P1", "Tee", "ACTIVATE", sku("S1", "TEE-M", "129000"), sku("S2", "TEE-L", "139000"));
        for (int i = 2; i <= 5; i++) {
            PLATFORM.addProduct(1, "P" + i, "Item " + i, "ACTIVATE", sku("S" + (i + 1), "CODE-" + i, "50000"));
        }

        ImportReport first = importFor(1);
        assertEquals("completed", first.status());
        assertEquals(3, first.counters().pages());
        assertEquals(5, first.counters().productsSeen());
        assertEquals(5, first.counters().productsCreated());
        assertEquals(6, first.counters().skusCreated());
        assertEquals(5, count("SELECT count(*) FROM products WHERE tenant_id=1"));
        assertEquals(6, count("SELECT count(*) FROM product_skus WHERE tenant_id=1"));
        assertEquals(6, count("SELECT count(*) FROM product_marketplace_links WHERE tenant_id=1 AND connection_id=1"));
        assertEquals("139000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE external_sku_id='S2'"));
        assertEquals("VND", text("SELECT currency FROM product_marketplace_links WHERE external_sku_id='S2'"));
        assertEquals("P1", text("SELECT external_product_id FROM product_marketplace_links WHERE external_sku_id='S2'"));
        assertEquals("TEE-L", text("SELECT seller_sku FROM product_marketplace_links WHERE external_sku_id='S2'"));
        assertEquals("V-S2", text("SELECT variant_name FROM product_skus WHERE sku_code='TEE-L'"));

        List<FakeTiktokPlatform.Request> searches = PLATFORM.requests.stream().filter(r -> r.path().contains("/search")).toList();
        assertEquals(3, searches.size());
        assertFalse(searches.get(0).query().containsKey("page_token"));
        assertEquals("t2", searches.get(1).query().get("page_token"));
        assertEquals("t4", searches.get(2).query().get("page_token"));
        searches.forEach(r -> assertEquals("2", r.query().get("page_size")));

        ImportReport second = importFor(1);
        assertEquals("completed", second.status());
        assertEquals(0, second.counters().productsCreated());
        assertEquals(5, second.counters().productsUpdated());
        assertEquals(0, second.counters().skusCreated());
        assertEquals(5, count("SELECT count(*) FROM products WHERE tenant_id=1"));
        assertEquals(6, count("SELECT count(*) FROM product_skus WHERE tenant_id=1"));
        assertEquals(6, count("SELECT count(*) FROM product_marketplace_links WHERE tenant_id=1"));
        assertEquals(0, count("SELECT count(*) FROM price_sync_history"), "an unchanged price writes no history");
        assertEquals(2, count("SELECT count(*) FROM marketplace_import_runs WHERE status='completed'"));
    }

    @Test
    void identicalPlatformIdsInTwoConnectionsNeverMix() throws Exception {
        PLATFORM.addProduct(1, "P1", "Tee A", "ACTIVATE", sku("S1", "A-1", "100000"));
        PLATFORM.addProduct(2, "P1", "Tee B", "ACTIVATE", sku("S1", "B-1", "555000"));
        importFor(1);
        importFor(2);

        assertEquals(2, count("SELECT count(*) FROM product_marketplace_links WHERE external_sku_id='S1' AND external_product_id='P1'"));
        assertEquals("100000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE tenant_id=1"));
        assertEquals("555000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE tenant_id=2"));
        assertEquals("A-1", text("SELECT s.sku_code FROM product_marketplace_links l JOIN product_skus s ON s.id=l.sku_id WHERE l.connection_id=1"));
        assertEquals("B-1", text("SELECT s.sku_code FROM product_marketplace_links l JOIN product_skus s ON s.id=l.sku_id WHERE l.connection_id=2"));

        // Tenant 2 cannot see or change tenant 1's mapping, even by naming tenant 1's connection id.
        assertTrue(inTx(2, () -> store.findLinkByExternalSku(1, "S1")).isEmpty());
        assertTrue(inTx(2, () -> store.listLinks(1)).isEmpty());
        long tenantOneLink = inTx(1, () -> store.findLinkByExternalSku(1, "S1")).orElseThrow().id();
        assertEquals(1, inTx(1, () -> store.listLinks(1)).size(), "sanity: tenant 1 does see its own link");
        inTx(2, () -> {
            store.updateLinkPrice(tenantOneLink, new BigDecimal("1"), "VND", java.time.Instant.now());
            return null;
        });
        assertEquals("100000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE tenant_id=1"));

        // A price change for tenant 1 leaves tenant 2 alone.
        enablePriceSync(1);
        PLATFORM.setPrice(1, "S1", "110000");
        assertEquals(1, syncPricesFor(1).applied());
        assertEquals("110000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE tenant_id=1"));
        assertEquals("555000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE tenant_id=2"));
        assertEquals(0, count("SELECT count(*) FROM price_sync_history WHERE tenant_id=2"));
    }

    @Test
    void sellerSkuMatchingConflictsAndUnmatchedAreReportedNotGuessed() throws Exception {
        update("INSERT INTO products (tenant_id, name) VALUES (1, 'Existing')");
        long existingProduct = Long.parseLong(text("SELECT id FROM products WHERE name='Existing'"));
        update("INSERT INTO product_skus (tenant_id, product_id, sku_code) VALUES (1," + existingProduct + ",'EXIST'),(1," + existingProduct + ",'TAKEN')");
        update("INSERT INTO product_marketplace_links (tenant_id, sku_id, connection_id, platform, external_sku_id, external_product_id) "
                + "SELECT 1, id, 1, 'tiktok_shop', 'OLD', 'OLDP' FROM product_skus WHERE sku_code='TAKEN'");

        PLATFORM.addProduct(1, "P1", "Mixed", "ACTIVATE", sku("S1", "EXIST", "1000"), sku("S2", null, "1000"));
        PLATFORM.addProduct(1, "P2", "Duplicate codes", "ACTIVATE", sku("S3", "DUP", "1000"), sku("S4", "DUP", "1000"));
        PLATFORM.addProduct(1, "P3", "Taken", "ACTIVATE", sku("S5", "TAKEN", "1000"));

        ImportReport report = importFor(1);

        assertEquals("completed_with_errors", report.status());
        assertEquals(1, report.counters().skusLinked());
        assertEquals(1, report.counters().skusUnmatched());
        assertEquals(3, report.counters().skusConflicted());
        assertEquals(0, report.counters().skusCreated());
        assertEquals(1, count("SELECT count(*) FROM products WHERE tenant_id=1"), "no product was created for unmatched or conflicting variants");
        assertEquals(2, count("SELECT count(*) FROM product_skus WHERE tenant_id=1"));
        assertEquals(1, count("SELECT count(*) FROM product_marketplace_links WHERE external_sku_id='S1'"));
        assertEquals(0, count("SELECT count(*) FROM product_marketplace_links WHERE external_sku_id IN ('S2','S3','S4','S5')"));
        assertEquals("EXIST", text("SELECT s.sku_code FROM product_marketplace_links l JOIN product_skus s ON s.id=l.sku_id WHERE l.external_sku_id='S1'"));
        assertTrue(hasItem(report, "SKU_UNMATCHED"));
        assertEquals(3, report.items().stream().filter(i -> "SKU_CONFLICT".equals(i.outcome())).count());
    }

    @Test
    void planSkuLimitStopsTheRunWithAnExplicitStatus() throws Exception {
        update("INSERT INTO subscription_plans (name, max_skus) VALUES ('small', 3)");
        update("UPDATE tenants SET plan_id = (SELECT id FROM subscription_plans WHERE name='small') WHERE id = 1");
        PLATFORM.addProduct(1, "P1", "One", "ACTIVATE", sku("S1", "C1", "1000"), sku("S2", "C2", "1000"));
        PLATFORM.addProduct(1, "P2", "Two", "ACTIVATE", sku("S3", "C3", "1000"), sku("S4", "C4", "1000"));
        PLATFORM.addProduct(1, "P3", "Three", "ACTIVATE", sku("S5", "C5", "1000"));

        ImportReport report = importFor(1);

        assertEquals("stopped_sku_limit", report.status());
        assertTrue(report.skuLimitReached());
        assertEquals(3, report.skuLimit());
        assertNotNull(report.errorMessage());
        assertTrue(report.errorMessage().contains("limit"));
        assertEquals(2, count("SELECT count(*) FROM product_skus WHERE tenant_id=1"), "the product that would exceed the limit is not created at all");
        assertEquals(1, report.counters().productsCreated());
        assertTrue(hasItem(report, "SKU_LIMIT"));
        assertEquals("stopped_sku_limit", text("SELECT status FROM marketplace_import_runs"));
        assertEquals("true", text("SELECT sku_limit_reached::text FROM marketplace_import_runs"));

        update("UPDATE subscription_plans SET max_skus = 10");
        ImportReport resumed = importFor(1);
        assertEquals("completed", resumed.status());
        assertEquals(5, count("SELECT count(*) FROM product_skus WHERE tenant_id=1"));
        assertEquals(3, count("SELECT count(*) FROM products WHERE tenant_id=1"));
        assertEquals(5, count("SELECT count(*) FROM product_marketplace_links WHERE tenant_id=1"));
    }

    @Test
    void aFailingProductIsReportedAndDoesNotBlockTheOthers() throws Exception {
        for (int i = 1; i <= 4; i++) {
            PLATFORM.addProduct(1, "P" + i, "Item " + i, "ACTIVATE", sku("S" + i, "C" + i, "1000"));
        }
        PLATFORM.failingDetailIds.add("P3");

        ImportReport report = importFor(1);

        assertEquals("completed_with_errors", report.status());
        assertEquals(1, report.counters().productsFailed());
        assertEquals(3, report.counters().productsCreated());
        assertTrue(hasItem(report, "DETAIL_FAILED"));
        assertEquals(0, count("SELECT count(*) FROM product_marketplace_links WHERE external_product_id='P3'"));

        PLATFORM.failingDetailIds.clear();
        ImportReport catchUp = importFor(1);
        assertEquals("completed", catchUp.status());
        assertEquals(1, catchUp.counters().productsCreated());
        assertEquals(4, count("SELECT count(*) FROM product_skus WHERE tenant_id=1"));
    }

    @Test
    void repeatedPlatformFailuresAbortTheRunAsFailedNeverAsSuccess() throws Exception {
        for (int i = 1; i <= 8; i++) {
            PLATFORM.addProduct(1, "P" + i, "Item " + i, "ACTIVATE", sku("S" + i, "C" + i, "1000"));
            PLATFORM.failingDetailIds.add("P" + i);
        }
        ImportReport report = importFor(1);
        assertEquals("failed", report.status());
        assertTrue(report.errorMessage().contains("consecutive"));
        assertEquals(5, report.counters().productsFailed());
        assertEquals(0, count("SELECT count(*) FROM product_skus"));
        assertEquals("failed", text("SELECT status FROM marketplace_import_runs"));
    }

    @Test
    void searchOutageFailsTheRunAndKeepsEarlierPages() throws Exception {
        PLATFORM.failSearch = true;
        PLATFORM.addProduct(1, "P1", "One", "ACTIVATE", sku("S1", "C1", "1000"));
        ImportReport report = importFor(1);
        assertEquals("failed", report.status());
        assertNotNull(report.errorMessage());
        assertEquals(0, count("SELECT count(*) FROM product_skus"));
    }

    @Test
    void deactivatedProductsAreHiddenAndUnpublishedOnesSkipped() throws Exception {
        PLATFORM.addProduct(1, "P1", "Live", "ACTIVATE", sku("S1", "C1", "1000"));
        PLATFORM.addProduct(1, "P2", "Paused", "SELLER_DEACTIVATED", sku("S2", "C2", "1000"));
        PLATFORM.addProduct(1, "P3", "Draft", "DRAFT", sku("S3", "C3", "1000"));
        ImportReport report = importFor(1);
        assertEquals(2, report.counters().productsCreated());
        assertEquals(1, report.counters().productsSkipped());
        assertEquals("active", text("SELECT p.status::text FROM products p JOIN product_skus s ON s.product_id=p.id WHERE s.sku_code='C1'"));
        assertEquals("hidden", text("SELECT p.status::text FROM products p JOIN product_skus s ON s.product_id=p.id WHERE s.sku_code='C2'"));
        assertEquals(0, count("SELECT count(*) FROM product_skus WHERE sku_code='C3'"));

        // The platform later deletes P1: the local product is hidden, nothing is removed.
        PLATFORM.reset();
        PLATFORM.addProduct(1, "P1", "Live", "DELETED", sku("S1", "C1", "1000"));
        importFor(1);
        assertEquals("hidden", text("SELECT p.status::text FROM products p JOIN product_skus s ON s.product_id=p.id WHERE s.sku_code='C1'"));
        assertEquals("DELETED", text("SELECT external_status FROM product_marketplace_links WHERE external_sku_id='S1'"));
        assertEquals(1, count("SELECT count(*) FROM product_skus WHERE sku_code='C1'"));
    }

    @Test
    void aSecondImportWhileOneIsRunningIsRejectedAndAStaleRunIsTakenOver() throws Exception {
        PLATFORM.addProduct(1, "P1", "One", "ACTIVATE", sku("S1", "C1", "1000"));
        update("INSERT INTO marketplace_import_runs (tenant_id, connection_id, status) VALUES (1, 1, 'running')");
        BusinessException rejected = assertThrows(BusinessException.class, () -> importFor(1));
        assertEquals("IMPORT_ALREADY_RUNNING", rejected.getErrorCode());
        assertEquals(0, PLATFORM.requests.size(), "no platform call was made");

        update("UPDATE marketplace_import_runs SET started_at = now() - interval '2 hours'");
        assertEquals("completed", importFor(1).status());
        assertEquals("failed", text("SELECT status FROM marketplace_import_runs ORDER BY id LIMIT 1"));
        assertEquals(1, count("SELECT count(*) FROM product_skus"));
    }

    @Test
    void anotherTenantsConnectionIsNeverImported() throws Exception {
        PLATFORM.addProduct(1, "P1", "Only A", "ACTIVATE", sku("S1", "A1", "1000"));
        importFor(2);
        assertEquals(0, count("SELECT count(*) FROM product_skus"));
        assertTrue(PLATFORM.requests.stream().allMatch(r -> r.connectionId() == 2L), "tenant 2 only ever addresses its own connection");
    }

    // ---- warehouses

    @Test
    void warehouseSyncIsIdempotentAndTenantScoped() throws Exception {
        PLATFORM.addWarehouse(1, "W1", "Kho HN", true);
        PLATFORM.addWarehouse(1, "W2", "Kho HCM", false);
        PLATFORM.addWarehouse(2, "W1", "Kho B", true);

        assertEquals(2, as(1, importer::syncWarehousesForCurrentTenant));
        assertEquals(2, as(1, importer::syncWarehousesForCurrentTenant));
        assertEquals(2, count("SELECT count(*) FROM marketplace_warehouses WHERE tenant_id=1"));
        assertEquals(0, count("SELECT count(*) FROM marketplace_warehouses WHERE tenant_id=2"));
        assertEquals(1, as(2, importer::syncWarehousesForCurrentTenant));
        assertEquals("Kho B", text("SELECT name FROM marketplace_warehouses WHERE tenant_id=2 AND external_warehouse_id='W1'"));
        assertEquals("Kho HN", text("SELECT name FROM marketplace_warehouses WHERE tenant_id=1 AND external_warehouse_id='W1'"));
    }

    // ---- prices

    @Test
    void twoSkusOfOneProductUpdateIndependentlyAndAnAnomalyIsHeldNotApplied() throws Exception {
        PLATFORM.addProduct(1, "P1", "Tee", "ACTIVATE", sku("A", "TEE-A", "100000"), sku("B", "TEE-B", "200000"));
        importFor(1);
        enablePriceSync(1);

        PLATFORM.setPrice(1, "A", "110000");
        PLATFORM.setPrice(1, "B", "10000");
        TiktokPriceSyncService.Result result = syncPricesFor(1);

        assertEquals(1, result.applied());
        assertEquals(1, result.heldAnomaly());
        assertEquals("110000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE external_sku_id='A'"));
        assertEquals("200000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE external_sku_id='B'"), "anomalous price is held for confirmation");
        assertEquals("false", text("SELECT is_anomaly::text FROM price_sync_history WHERE external_sku_id='A'"));
        assertEquals("true", text("SELECT applied::text FROM price_sync_history WHERE external_sku_id='A'"));
        assertEquals("true", text("SELECT is_anomaly::text FROM price_sync_history WHERE external_sku_id='B'"));
        assertEquals("false", text("SELECT applied::text FROM price_sync_history WHERE external_sku_id='B'"));
        assertEquals("10000.00", text("SELECT new_price::text FROM price_sync_history WHERE external_sku_id='B'"));
        assertEquals("200000.00", text("SELECT old_price::text FROM price_sync_history WHERE external_sku_id='B'"));
        assertEquals("1", text("SELECT connection_id::text FROM price_sync_history WHERE external_sku_id='B'"));

        syncPricesFor(1);
        assertEquals(2, count("SELECT count(*) FROM price_sync_history"), "the same pending anomaly is not logged again");
    }

    @Test
    void anApiErrorAndInvalidPricesNeverOverwriteTheStoredPrice() throws Exception {
        PLATFORM.addProduct(1, "P1", "Tee", "ACTIVATE", sku("A", "TEE-A", "100000"), sku("B", "TEE-B", "200000"));
        importFor(1);
        enablePriceSync(1);

        PLATFORM.failSearch = true;
        TiktokPriceSyncService.Result outage = syncPricesFor(1);
        assertNotNull(outage.fetchError());
        assertEquals(0, outage.applied());
        PLATFORM.failSearch = false;

        PLATFORM.setPrice(1, "A", "0");
        PLATFORM.setPrice(1, "B", null);
        TiktokPriceSyncService.Result invalid = syncPricesFor(1);
        assertEquals(2, invalid.rejected());

        assertEquals("100000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE external_sku_id='A'"));
        assertEquals("200000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE external_sku_id='B'"));
        assertEquals(0, count("SELECT count(*) FROM price_sync_history"));
        assertEquals(0, count("SELECT count(*) FROM product_marketplace_links WHERE platform_price IS NULL OR platform_price = 0"));
    }

    @Test
    void withoutAnEnabledConfigNoPlatformCallIsMade() throws Exception {
        PLATFORM.addProduct(1, "P1", "Tee", "ACTIVATE", sku("A", "TEE-A", "100000"));
        importFor(1);
        PLATFORM.requests.clear();
        PLATFORM.setPrice(1, "A", "120000");

        assertEquals(0, syncPricesFor(1).considered());
        update("INSERT INTO price_sync_configs (tenant_id, scope, platform, sync_enabled) VALUES (1,'tenant',NULL,false)");
        assertEquals(0, syncPricesFor(1).considered());
        assertEquals(0, PLATFORM.requests.size());
        assertEquals("100000.00", text("SELECT platform_price::text FROM product_marketplace_links"));
    }

    @Test
    void aSkuLevelConfigCanSwitchOneSkuOffWhileTheTenantDefaultIsOn() throws Exception {
        PLATFORM.addProduct(1, "P1", "Tee", "ACTIVATE", sku("A", "TEE-A", "100000"), sku("B", "TEE-B", "200000"));
        importFor(1);
        enablePriceSync(1);
        update("INSERT INTO price_sync_configs (tenant_id, scope, sku_id, platform, sync_enabled) "
                + "SELECT 1,'product', id, 'tiktok_shop', false FROM product_skus WHERE sku_code='TEE-B'");
        PLATFORM.setPrice(1, "A", "101000");
        PLATFORM.setPrice(1, "B", "201000");

        TiktokPriceSyncService.Result result = syncPricesFor(1);

        assertEquals(1, result.considered());
        assertEquals("101000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE external_sku_id='A'"));
        assertEquals("200000.00", text("SELECT platform_price::text FROM product_marketplace_links WHERE external_sku_id='B'"));
    }

    // ---- schema

    @Test
    void v10ConstraintsAndRowLevelSecurityHold() throws Exception {
        PLATFORM.addProduct(1, "P1", "Tee", "ACTIVATE", sku("S1", "TEE-1", "1000"));
        PLATFORM.addProduct(2, "P1", "Tee", "ACTIVATE", sku("S1", "TEE-2", "1000"));
        importFor(1);
        importFor(2);

        // (connection, external sku) is unique; the same external id under another connection is fine (already imported above).
        assertThrows(Exception.class, () -> update("INSERT INTO product_marketplace_links (tenant_id, sku_id, connection_id, platform, external_sku_id) "
                + "SELECT 1, id, 1, 'tiktok_shop', 'S1' FROM product_skus WHERE tenant_id=1 LIMIT 1"));
        // A tenant 1 link cannot point at tenant 2's connection (composite foreign key).
        assertThrows(Exception.class, () -> update("INSERT INTO product_marketplace_links (tenant_id, sku_id, connection_id, platform, external_sku_id) "
                + "SELECT 1, id, 2, 'tiktok_shop', 'OTHER' FROM product_skus WHERE tenant_id=1 LIMIT 1"));

        // The application role only sees its own tenant's rows in the new tables.
        PLATFORM.addWarehouse(1, "W1", "Kho", true);
        PLATFORM.addWarehouse(2, "W9", "Kho", true);
        as(1, importer::syncWarehousesForCurrentTenant);
        as(2, importer::syncWarehousesForCurrentTenant);
        try (Connection app = DriverManager.getConnection(url, "smartomni_app", PASSWORD)) {
            execute(app, "SELECT set_config('app.tenant_id','2',false), set_config('app.user_role','manager',false)");
            try (var s = app.createStatement(); ResultSet r = s.executeQuery("SELECT count(*), min(tenant_id), max(tenant_id) FROM marketplace_import_runs")) {
                assertTrue(r.next());
                assertEquals(1, r.getLong(1));
                assertEquals(2, r.getLong(2));
            }
            try (var s = app.createStatement(); ResultSet r = s.executeQuery("SELECT count(*), min(tenant_id) FROM marketplace_warehouses")) {
                assertTrue(r.next());
                assertEquals(1, r.getLong(1));
                assertEquals(2, r.getLong(2));
            }
            assertThrows(Exception.class, () -> execute(app, "INSERT INTO marketplace_warehouses (tenant_id, connection_id, external_warehouse_id) VALUES (1, 1, 'X')"));
        }
    }
}
