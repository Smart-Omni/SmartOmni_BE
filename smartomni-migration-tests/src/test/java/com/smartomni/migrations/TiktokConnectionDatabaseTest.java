package com.smartomni.migrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.client.tiktok.TiktokHttpTransport;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.tiktok.ReconnectNotifier;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.ConsumedState;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.LinkRequest;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.StateOutcome;
import com.smartomni.integration.service.tiktok.TiktokTokenService;
import com.smartomni.integration.service.tiktok.TokenCipher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL-level evidence for phase 03 (SmartOmni side): V10 schema, RLS isolation, single-use state under
 * concurrency, one TikTok row/shop constraints, enum round trips and the real compare-and-swap token refresh.
 */
class TiktokConnectionDatabaseTest {

    private static final String PASSWORD = "temporary-test-password";
    private static final String AES = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void start() throws Exception {
        Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            execute(c, "CREATE ROLE smartomni_migrator LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
            execute(c, "CREATE ROLE smartomni_app LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        }
    }

    @AfterAll
    static void stop() throws Exception {
        TenantContext.clear();
        if (postgres != null) {
            postgres.close();
        }
    }

    // ---- helpers

    private static void execute(Connection c, String sql) throws Exception {
        try (var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(Connection c, String sql) throws Exception {
        try (var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    private static String text(Connection c, String sql) throws Exception {
        try (var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static Connection connect(String url, String role) throws Exception {
        return DriverManager.getConnection(url, role, role.equals("postgres") ? "postgres" : PASSWORD);
    }

    /** Fresh database, migrated, with tenants 1 and 2 and one Tenant Admin each (users 1 and 2). */
    private String newDatabase() throws Exception {
        String name = "tt_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            execute(c, "CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + name;
        try (Connection c = connect(url, "postgres")) {
            execute(c, "ALTER SCHEMA public OWNER TO smartomni_migrator");
            execute(c, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            execute(c, "GRANT USAGE ON SCHEMA public TO smartomni_app");
        }
        Flyway.configure().dataSource(url, "smartomni_migrator", PASSWORD).defaultSchema("public")
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        try (Connection c = connect(url, "postgres")) {
            execute(c, "INSERT INTO tenants (name,subdomain) VALUES ('A','a'),('B','b')");
            execute(c, "INSERT INTO users(tenant_id,email,password_hash,role) VALUES (1,'a@example.test','h','admin'),(2,'b@example.test','h','admin')");
        }
        return url;
    }

    private static ConfigurableApplicationContext boot(String url) {
        return new SpringApplicationBuilder(TiktokTestApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=smartomni_app",
                "--spring.datasource.password=" + PASSWORD,
                "--spring.datasource.hikari.maximum-pool-size=8",
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.open-in-view=false",
                "--spring.sql.init.mode=never",
                "--logging.level.root=WARN");
    }

    private static LinkRequest link(String shopId, ConnectionStatus status, TokenCipher cipher, Instant accessExpires) {
        return new LinkRequest(shopId, "Shop " + shopId, "CIPHER-" + shopId, "VN", "LOCAL", "OID-" + shopId,
                "orders.read", cipher.encrypt("access-" + shopId), cipher.encrypt("refresh-" + shopId),
                accessExpires, NOW.plusSeconds(86400L * 30), status, NOW);
    }

    private static <T> T asAdmin(long tenant, long user, java.util.function.Supplier<T> action) {
        try (var scope = TenantContext.openScope(tenant, "ADMIN", user)) {
            return action.get();
        }
    }

    // ---- tests

    @Test
    void v9AddsStatesTablesAndConstraintsWithRlsForcedAndNewEnumValuesRoundTrip() throws Exception {
        String url = newDatabase();
        TokenCipher cipher = new TokenCipher(AES);
        try (var context = boot(url); Connection admin = connect(url, "postgres")) {
            TiktokConnectionStore store = context.getBean(TiktokConnectionStore.class);
            assertEquals(1, count(admin, "SELECT count(*) FROM pg_class WHERE relname='marketplace_authorization_states' AND relrowsecurity AND relforcerowsecurity"));
            assertEquals(1, count(admin, "SELECT count(*) FROM pg_policies WHERE tablename='marketplace_authorization_states' AND policyname='tenant_isolation'"));
            assertEquals(3, count(admin, "SELECT count(*) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typname='connection_status' AND e.enumlabel IN ('missing_scope','refresh_failed','revoked')"));

            Long connectionId = asAdmin(1, 1, () -> store.prepareStart(1L, 1L, "h".repeat(64), NOW.plusSeconds(600)));
            assertEquals("disconnected", text(admin, "SELECT status::text FROM marketplace_connections WHERE id=" + connectionId));

            for (ConnectionStatus status : List.of(ConnectionStatus.MISSING_SCOPE, ConnectionStatus.CONNECTED)) {
                asAdmin(1, 1, () -> store.link(1L, connectionId, link("7001", status, cipher, NOW.plusSeconds(3600))));
                assertEquals(status.name().toLowerCase(), text(admin, "SELECT status::text FROM marketplace_connections WHERE id=" + connectionId));
            }
            // tokens are ciphertext in the table
            String stored = text(admin, "SELECT access_token_encrypted FROM marketplace_connections WHERE id=" + connectionId);
            assertTrue(stored.startsWith("v1:") && !stored.contains("access-7001"));

            // REFRESH_FAILED and REVOKED values
            assertTrue(asAdmin(1, 1, () -> store.markTokenFailure(connectionId, 2, ConnectionStatus.REFRESH_FAILED, "REFRESH_REJECTED", NOW)));
            assertEquals("refresh_failed", text(admin, "SELECT status::text FROM marketplace_connections WHERE id=" + connectionId));
            assertNull(text(admin, "SELECT access_token_encrypted FROM marketplace_connections WHERE id=" + connectionId));
            asAdmin(1, 1, () -> store.terminate(1L, connectionId, ConnectionStatus.REVOKED, "REVOKED", NOW));
            assertEquals("revoked", text(admin, "SELECT status::text FROM marketplace_connections WHERE id=" + connectionId));
        }
    }

    @Test
    void tenantsCannotSeeUseOrConsumeEachOthersConnectionOrState() throws Exception {
        String url = newDatabase();
        TokenCipher cipher = new TokenCipher(AES);
        try (var context = boot(url); Connection admin = connect(url, "postgres")) {
            TiktokConnectionStore store = context.getBean(TiktokConnectionStore.class);
            String hashA = "a".repeat(64);
            Long connectionA = asAdmin(1, 1, () -> store.prepareStart(1L, 1L, hashA, NOW.plusSeconds(600)));
            asAdmin(1, 1, () -> store.link(1L, connectionA, link("7001", ConnectionStatus.CONNECTED, cipher, NOW.plusSeconds(3600))));

            // Tenant B (with a valid B admin) presents A's state: indistinguishable from an unknown state.
            ConsumedState foreign = asAdmin(2, 2, () -> store.consumeState(2L, 2L, hashA, NOW));
            assertEquals(StateOutcome.INVALID, foreign.outcome());
            // ... even if B lies about the tenant argument: RLS hides A's row.
            assertEquals(StateOutcome.INVALID, asAdmin(2, 2, () -> store.consumeState(1L, 2L, hashA, NOW)).outcome());
            assertNull(text(admin, "SELECT consumed_at::text FROM marketplace_authorization_states WHERE state_hash='" + hashA + "'"));

            assertTrue(asAdmin(2, 2, () -> store.findCurrent(2L)).isEmpty());
            assertTrue(asAdmin(2, 2, () -> store.findCurrent(1L)).isEmpty(), "B cannot read A's row even when asking for tenant 1");
            assertTrue(asAdmin(2, 2, () -> store.loadTokens(1L, connectionA)).isEmpty());
            assertTrue(asAdmin(2, 2, () -> store.loadTokens(2L, connectionA)).isEmpty());
            assertThrows(BusinessException.class, () -> asAdmin(2, 2, () -> store.terminate(2L, connectionA, ConnectionStatus.DISCONNECTED, null, NOW)));
            assertFalse(asAdmin(2, 2, () -> store.rotateTokens(connectionA, 1, "x", "y", NOW, NOW, NOW)), "update affects 0 rows under RLS");
            assertEquals(ConnectionStatus.CONNECTED.name().toLowerCase(), text(admin, "SELECT status::text FROM marketplace_connections WHERE id=" + connectionA));

            // Writing a state row for another tenant is refused by the policy.
            assertThrows(RuntimeException.class, () -> asAdmin(2, 2, () -> store.prepareStart(1L, 1L, "z".repeat(64), NOW.plusSeconds(60))));
        }
    }

    @Test
    void stateIsSingleUseExpiringAndBoundToTheUserEvenUnderConcurrentCallbacks() throws Exception {
        String url = newDatabase();
        try (var context = boot(url)) {
            TiktokConnectionStore store = context.getBean(TiktokConnectionStore.class);
            String hash = "c".repeat(64);
            asAdmin(1, 1, () -> store.prepareStart(1L, 1L, hash, NOW.plusSeconds(600)));

            // wrong user -> invalid, state stays usable
            assertEquals(StateOutcome.INVALID, asAdmin(1, 1, () -> store.consumeState(1L, 99L, hash, NOW)).outcome());

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ConsumedState>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return asAdmin(1, 1, () -> store.consumeState(1L, 1L, hash, NOW));
                }));
            }
            go.countDown();
            List<StateOutcome> outcomes = new ArrayList<>();
            for (Future<ConsumedState> f : futures) {
                outcomes.add(f.get().outcome());
            }
            pool.shutdown();
            assertEquals(1, Collections.frequency(outcomes, StateOutcome.OK), "exactly one callback wins: " + outcomes);
            assertEquals(threads - 1, Collections.frequency(outcomes, StateOutcome.REUSED));
            assertEquals(StateOutcome.REUSED, asAdmin(1, 1, () -> store.consumeState(1L, 1L, hash, NOW.plusSeconds(1))).outcome());

            String expiring = "e".repeat(64);
            asAdmin(1, 1, () -> store.prepareStart(1L, 1L, expiring, NOW.plusSeconds(60)));
            assertEquals(StateOutcome.EXPIRED, asAdmin(1, 1, () -> store.consumeState(1L, 1L, expiring, NOW.plusSeconds(61))).outcome());
            assertEquals(StateOutcome.INVALID, asAdmin(1, 1, () -> store.consumeState(1L, 1L, "f".repeat(64), NOW)).outcome());
        }
    }

    @Test
    void oneTikTokRowPerTenantAndOneTenantPerShopEnforcedByTheDatabase() throws Exception {
        String url = newDatabase();
        TokenCipher cipher = new TokenCipher(AES);
        try (var context = boot(url); Connection admin = connect(url, "postgres")) {
            TiktokConnectionStore store = context.getBean(TiktokConnectionStore.class);

            // a second TikTok row for the same tenant is impossible (also with NULL shop_id)
            Long a = asAdmin(1, 1, () -> store.prepareStart(1L, 1L, "1".repeat(64), NOW.plusSeconds(600)));
            assertEquals(a, asAdmin(1, 1, () -> store.prepareStart(1L, 1L, "2".repeat(64), NOW.plusSeconds(600))), "start reuses the row");
            assertThrows(Exception.class, () -> execute(admin, "INSERT INTO marketplace_connections(tenant_id,platform) VALUES (1,'tiktok_shop')"));
            execute(admin, "INSERT INTO marketplace_connections(tenant_id,platform,shop_id) VALUES (1,'shopee','shopee-1')"); // other platforms unaffected

            // tenant A links shop 7001; tenant B cannot take the same shop
            asAdmin(1, 1, () -> store.link(1L, a, link("7001", ConnectionStatus.CONNECTED, cipher, NOW.plusSeconds(3600))));
            Long b = asAdmin(2, 2, () -> store.prepareStart(2L, 2L, "3".repeat(64), NOW.plusSeconds(600)));
            assertThrows(DataIntegrityViolationException.class,
                    () -> asAdmin(2, 2, () -> store.link(2L, b, link("7001", ConnectionStatus.CONNECTED, cipher, NOW.plusSeconds(3600)))));
            asAdmin(2, 2, () -> store.link(2L, b, link("7002", ConnectionStatus.CONNECTED, cipher, NOW.plusSeconds(3600))));

            // A cannot silently swap to another shop while connected
            BusinessException mismatch = assertThrows(BusinessException.class,
                    () -> asAdmin(1, 1, () -> store.link(1L, a, link("7003", ConnectionStatus.CONNECTED, cipher, NOW.plusSeconds(3600)))));
            assertEquals("TIKTOK_SHOP_MISMATCH", mismatch.getErrorCode());

            // after A disconnects, shop 7001 is free for B's... and A may pick a different shop
            asAdmin(1, 1, () -> store.terminate(1L, a, ConnectionStatus.DISCONNECTED, null, NOW));
            assertNull(text(admin, "SELECT access_token_encrypted FROM marketplace_connections WHERE id=" + a));
            asAdmin(1, 1, () -> store.link(1L, a, link("7003", ConnectionStatus.CONNECTED, cipher, NOW.plusSeconds(3600))));
            assertEquals("7003", text(admin, "SELECT shop_id FROM marketplace_connections WHERE id=" + a));
        }
    }

    @Test
    void realCompareAndSwapRefreshRunsOnceUnderConcurrencyAndRejectionIsFinal() throws Exception {
        String url = newDatabase();
        TokenCipher cipher = new TokenCipher(AES);
        try (var context = boot(url); Connection admin = connect(url, "postgres")) {
            TiktokConnectionStore store = context.getBean(TiktokConnectionStore.class);
            Long id = asAdmin(1, 1, () -> store.prepareStart(1L, 1L, "9".repeat(64), NOW.plusSeconds(600)));
            asAdmin(1, 1, () -> store.link(1L, id, link("7001", ConnectionStatus.CONNECTED, cipher, NOW.minusSeconds(10))));

            AtomicInteger refreshCalls = new AtomicInteger();
            long refreshExpiry = NOW.plusSeconds(86400L * 30).getEpochSecond();
            TiktokHttpTransport transport = (method, uri, headers, body) -> {
                refreshCalls.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new TiktokHttpTransport.Response(200, "{\"code\":0,\"message\":\"ok\",\"data\":{\"access_token\":\"fresh-access\","
                        + "\"access_token_expire_in\":" + NOW.plusSeconds(86400).getEpochSecond()
                        + ",\"refresh_token\":\"fresh-refresh\",\"refresh_token_expire_in\":" + refreshExpiry + "}}");
            };
            TiktokProperties properties = new TiktokProperties();
            properties.setAppKey("k");
            properties.setAppSecret("s");
            List<String> alerts = Collections.synchronizedList(new ArrayList<>());
            ReconnectNotifier notifier = (t, c, r) -> alerts.add(t + ":" + c + ":" + r);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            TiktokTokenService service = new TiktokTokenService(store,
                    new TiktokAuthClient(properties, transport, new ObjectMapper()), cipher, notifier, properties, clock);

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return asAdmin(1, 1, () -> service.credentials(id).accessToken());
                }));
            }
            go.countDown();
            for (Future<String> f : futures) {
                assertEquals("fresh-access", f.get());
            }
            pool.shutdown();
            assertEquals(1, refreshCalls.get(), "one refresh request for concurrent callers");
            assertEquals(2, count(admin, "SELECT token_version FROM marketplace_connections WHERE id=" + id), "version 1 from link, 2 from the single rotation");
            assertTrue(text(admin, "SELECT refresh_token_encrypted FROM marketplace_connections WHERE id=" + id).startsWith("v1:"));

            // Now the platform refuses: token expired again, rejection is final and alerts once.
            execute(admin, "UPDATE marketplace_connections SET access_token_expires_at = '" + NOW.minusSeconds(5) + "' WHERE id=" + id);
            AtomicInteger rejected = new AtomicInteger();
            TiktokHttpTransport refusing = (method, uri, headers, body) -> {
                rejected.incrementAndGet();
                return new TiktokHttpTransport.Response(200, "{\"code\":36004001,\"message\":\"rt has expired\"}");
            };
            TiktokTokenService refusingService = new TiktokTokenService(store,
                    new TiktokAuthClient(properties, refusing, new ObjectMapper()), cipher, notifier, properties, clock);
            for (int i = 0; i < 3; i++) {
                assertThrows(BusinessException.class, () -> asAdmin(1, 1, () -> refusingService.credentials(id)));
            }
            assertEquals(1, rejected.get());
            assertEquals(1, alerts.size());
            assertEquals("refresh_failed", text(admin, "SELECT status::text FROM marketplace_connections WHERE id=" + id));
        }
    }
}
