package com.smartomni.migrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.service.catalog.CatalogMappingStore;
import com.smartomni.integration.service.tiktok.TiktokInventoryWebhookService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
class TiktokInventoryDatabaseIntegrationTest {
    private static final String PASSWORD = "temporary-test-password";
    private final ObjectMapper mapper = new ObjectMapper();
    @Test
    void warehouseSnapshotsKeepAvailableStockCorrectWithReservationsAndZero() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(0).start()) {
            String db = "inventory_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection admin = postgres.getPostgresDatabase().getConnection()) {
                exec(admin, "CREATE ROLE smartomni_migrator LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
                exec(admin, "CREATE ROLE smartomni_app LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
                exec(admin, "CREATE DATABASE " + db + " OWNER smartomni_migrator");
            }
            String url = postgres.getJdbcUrl("postgres", db);
            Flyway.configure().dataSource(url, "smartomni_migrator", PASSWORD)
                    .locations("classpath:db/migration").load().migrate();
            long tenant;
            long connection;
            long sku;
            try (Connection seed = postgres.getDatabase("postgres", db).getConnection();
                 Statement statement = seed.createStatement()) {
                tenant = single(statement, "INSERT INTO tenants (name, subdomain) VALUES ('Inventory', 'inventory') RETURNING id");
                connection = single(statement, "INSERT INTO marketplace_connections (tenant_id, platform, shop_id, status) "
                        + "VALUES (" + tenant + ", 'tiktok_shop', 'shop-test', 'connected') RETURNING id");
                long product = single(statement, "INSERT INTO products (tenant_id, name) VALUES (" + tenant + ", 'Product') RETURNING id");
                sku = single(statement, "INSERT INTO product_skus (tenant_id, product_id, sku_code) VALUES ("
                        + tenant + ", " + product + ", 'SKU-1') RETURNING id");
                exec(seed, "INSERT INTO inventory (tenant_id, sku_id, quantity_on_hand, reserved_quantity, version) "
                        + "VALUES (" + tenant + ", " + sku + ", 12, 2, 0)");
            }
            try (Connection app = DriverManager.getConnection(url, "smartomni_app", PASSWORD)) {
                exec(app, "SELECT set_config('app.tenant_id', '" + tenant + "', false)");
                exec(app, "SELECT set_config('app.user_role', 'tenant_admin', false)");
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(app, true));
                CatalogMappingStore mapping = mock(CatalogMappingStore.class);
                when(mapping.findLinkByExternalSku(connection, "EXTERNAL-SKU")).thenReturn(Optional.of(
                        new CatalogMappingStore.LinkRef(1, sku, 1, connection, "EXTERNAL-SKU", "PRODUCT", null, null, null, null)));
                TiktokInventoryWebhookService service = new TiktokInventoryWebhookService(mapping, jdbc);

                service.apply(tenant, connection, event("first", 100, 7, 3, 4));
                assertEquals(9, number(jdbc, "SELECT quantity_on_hand FROM inventory WHERE sku_id = " + sku));
                assertEquals(2, number(jdbc, "SELECT reserved_quantity FROM inventory WHERE sku_id = " + sku));
                assertEquals(7, number(jdbc, "SELECT quantity_on_hand - reserved_quantity FROM inventory WHERE sku_id = " + sku));
                assertEquals(2, number(jdbc, "SELECT COUNT(*) FROM tiktok_inventory_warehouse_snapshots WHERE sku_id = " + sku));
                service.apply(tenant, connection, event("first", 100, 7, 3, 4));
                assertEquals(1, number(jdbc, "SELECT version FROM inventory WHERE sku_id = " + sku));
                service.apply(tenant, connection, event("second", 101, 0, 0, 0));
                assertEquals(2, number(jdbc, "SELECT quantity_on_hand FROM inventory WHERE sku_id = " + sku));
                assertEquals(0, number(jdbc, "SELECT quantity_on_hand - reserved_quantity FROM inventory WHERE sku_id = " + sku));
                assertEquals(0, number(jdbc, "SELECT SUM(available_quantity) FROM tiktok_inventory_warehouse_snapshots WHERE sku_id = " + sku));
                service.apply(tenant, connection, event("stale", 99, 20, 10, 10));
                assertEquals(0, number(jdbc, "SELECT quantity_on_hand - reserved_quantity FROM inventory WHERE sku_id = " + sku));
            }
        }
    }

    private TiktokWebhookPayload event(String id, long timestamp, int total, int first, int second) throws Exception {
        var data = mapper.readTree("{\"event_id\":\"" + id + "\",\"sku_id\":\"EXTERNAL-SKU\","
                + "\"quantity_snapshot_after_change\":{\"available_quantity\":" + total
                + ",\"warehouses\":[{\"warehouse_id\":\"WH-1\",\"available_quantity\":" + first
                + "},{\"warehouse_id\":\"WH-2\",\"available_quantity\":" + second + "}]}}");
        return TiktokWebhookPayload.builder().type(71).timestamp(timestamp).shopId("shop-test")
                .ttsNotificationId(id).data(data).build();
    }

    private static int number(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private static long single(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
    }
    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
