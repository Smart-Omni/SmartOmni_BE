package com.smartomni.migrations;

import com.smartomni.auth.dto.LoginRequest;
import com.smartomni.auth.service.AuthService;
import com.smartomni.catalog.entity.Product;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.persistence.RlsSession;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.order.entity.Order;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTest {

    private static EmbeddedPostgres postgres;
    private static final String TEST_PASSWORD = "temporary-test-password";
    private static final List<String> MIGRATIONS = List.of(
            "V1__create_enums.sql", "V2__create_tables.sql", "V3__create_foreign_keys_and_indexes.sql",
            "V4__create_rls_functions_and_grants.sql", "V5__enable_rls_policies.sql",
            "V6__scope_authentication_and_background_access.sql",
            "V7__create_order_loyalty.sql", "V8__loyalty_milestone_rewards.sql",
            "V9__add_user_roles.sql", "V10__tiktok_shop_connection.sql",
            "V11__tiktok_catalog_mapping.sql", "V12__add_on_hold_to_order_status.sql",
            "V13__add_image_url_to_product_and_order_items.sql",
            "V14__add_return_evidences_and_reject_reason.sql",
            "V15__shopee_connection.sql");

    @BeforeAll
    static void startPostgres() throws Exception {
        Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        try (Connection connection = postgres.getPostgresDatabase().getConnection()) {
            execute(connection, "CREATE ROLE smartomni_migrator LOGIN PASSWORD '" + TEST_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
            execute(connection, "CREATE ROLE smartomni_app LOGIN PASSWORD '" + TEST_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        TenantContext.clear();
        if (postgres != null) {
            postgres.close();
        }
    }

    private String newDatabase() throws Exception {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection()) {
            execute(connection, "CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + name;
        try (Connection connection = connect(url, "postgres")) {
            execute(connection, "ALTER SCHEMA public OWNER TO smartomni_migrator");
            execute(connection, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            execute(connection, "GRANT USAGE ON SCHEMA public TO smartomni_app");
        }
        return url;
    }

    private static Connection connect(String url, String role) throws Exception {
        return DriverManager.getConnection(url, role, role.equals("postgres") ? "postgres" : TEST_PASSWORD);
    }

    private static Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, "smartomni_migrator", TEST_PASSWORD)
                .defaultSchema("public").locations("classpath:db/migration")
                .baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private static ConfigurableApplicationContext boot(String url) {
        return new SpringApplicationBuilder(MigrationTestApplication.class).web(WebApplicationType.NONE).run(
                "--spring.config.location=optional:classpath:/migration-test.yml",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=smartomni_app",
                "--spring.datasource.password=" + TEST_PASSWORD,
                "--spring.datasource.hikari.maximum-pool-size=5",
                "--spring.flyway.url=" + url,
                "--spring.flyway.user=smartomni_migrator",
                "--spring.flyway.password=" + TEST_PASSWORD,
                "--spring.flyway.default-schema=public",
                "--spring.flyway.baseline-on-migrate=false",
                "--spring.flyway.clean-disabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.open-in-view=false",
                "--spring.sql.init.mode=never",
                "--smartomni.jwt.secret=temporary_test_signing_key_at_least_32_bytes",
                "--logging.level.root=WARN");
    }

    @Test
    void concurrentServiceMigrationsCreateOneCompleteSchemaAndRestartIsNoOp() throws Exception {
        String url = newDatabase();
        var executor = Executors.newFixedThreadPool(7);
        CountDownLatch ready = new CountDownLatch(7);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 7; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return flyway(url).migrate().migrationsExecuted;
                }));
            }
            assertTrue(ready.await(30, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int applied = 0;
            for (var future : futures) {
                applied += future.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(15, applied);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertEquals(0, flyway(url).migrate().migrationsExecuted);
        try (Connection admin = connect(url, "postgres")) {
            assertEquals(47, count(admin, "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'"));
            assertEquals(47, count(admin, "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='r' AND relrowsecurity AND relforcerowsecurity"));
            assertEquals(24, count(admin, "SELECT count(*) FROM pg_type WHERE typnamespace='public'::regnamespace AND typtype='e'"));
            assertTrue(count(admin, "SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace AND contype='f'") > 50);
            assertEquals(15, count(admin, "SELECT count(*) FROM flyway_schema_history WHERE success"));
            execute(admin, Files.readString(Path.of("..", "scripts", "db", "verify-schema.sql")));
        }
        try (Connection app = connect(url, "smartomni_app")) {
            assertEquals(0, count(app, "SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)"));
            assertThrows(Exception.class, () -> execute(app, "SELECT * FROM flyway_schema_history"));
            assertThrows(Exception.class, () -> execute(app, "CREATE TABLE forbidden(id bigint)"));
        }
    }

    @Test
    void bootMigratesBeforeValidatingEntitiesFromAllSevenServicesAndEnumsRoundTrip() throws Exception {
        String url = newDatabase();
        try (var context = boot(url); var scope = TenantContext.openScope(null, "SUPER_ADMIN", null)) {
            TransactionTemplate transactions = context.getBean(TransactionTemplate.class);
            EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            try (Connection admin = connect(url, "postgres")) {
                execute(admin, "INSERT INTO tenants (name,subdomain) VALUES ('A','a')");
                execute(admin, "INSERT INTO products (tenant_id,name) VALUES (1,'Seed')");
                execute(admin, "INSERT INTO product_skus (tenant_id,product_id,sku_code) VALUES (1,1,'SKU')");
            }
            transactions.executeWithoutResult(status -> {
                Product product = new Product();
                product.setTenantId(1L);
                product.setName("Enum round trip");
                product.setStatus(Product.ProductStatus.HIDDEN);
                em.persist(product);
                Order order = new Order();
                order.setTenantId(1L);
                order.setPlatform(Order.Platform.SHOPEE);
                order.setStatus(Order.OrderStatus.TO_SHIP);
                order.setSource(Order.OrderSource.WEBHOOK);
                em.persist(order);
                InventoryOutboxEvent event = new InventoryOutboxEvent();
                event.setTenantId(1L);
                event.setSkuId(1L);
                event.setPlatform(InventoryOutboxEvent.Platform.SHOPEE);
                event.setStatus(InventoryOutboxEvent.OutboxStatus.PENDING);
                em.persist(event);
                em.flush();
                em.clear();
                assertEquals(Product.ProductStatus.HIDDEN, em.find(Product.class, product.getId()).getStatus());
                assertEquals(Order.OrderStatus.TO_SHIP, em.find(Order.class, order.getId()).getStatus());
                assertEquals(InventoryOutboxEvent.Platform.SHOPEE, em.find(InventoryOutboxEvent.class, event.getId()).getPlatform());
            });
        }
        try (Connection admin = connect(url, "postgres")) {
            assertEquals(1, count(admin, "SELECT count(*) FROM orders WHERE status='to_ship' AND platform='shopee'"));
        }
    }

    @Test
    void tenantContextIsTransactionLocalAndCannotReadOrWriteAnotherTenant() throws Exception {
        String url = newDatabase();
        try (var context = boot(url)) {
            try (Connection admin = connect(url, "postgres")) {
                execute(admin, "INSERT INTO tenants (name,subdomain) VALUES ('A','a'),('B','b')");
                execute(admin, "INSERT INTO products (tenant_id,name) VALUES (1,'A'),(2,'B')");
            }
            EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            TransactionTemplate transactions = context.getBean(TransactionTemplate.class);
            for (long tenant : List.of(1L, 2L)) {
                try (var scope = TenantContext.openScope(tenant, "MANAGER", null)) {
                    transactions.executeWithoutResult(status -> {
                        assertEquals(1, em.createQuery("from Product", Product.class).getResultList().size());
                        assertNull(em.find(Product.class, tenant == 1 ? 2L : 1L));
                    });
                }
            }
            try (var scope = TenantContext.openScope(1L, "MANAGER", null)) {
                assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(status -> {
                    em.createNativeQuery("insert into products (tenant_id,name) values (2,'Forbidden')").executeUpdate();
                }));
            }
            transactions.executeWithoutResult(status -> {
                assertTrue(em.createQuery("from Product", Product.class).getResultList().isEmpty());
                assertEquals("", em.createNativeQuery("select current_setting('app.tenant_id', true)").getSingleResult());
            });
        }
    }

    @Test
    void authenticationAndPasswordResetWorkWithoutSuperAdminContext() throws Exception {
        String url = newDatabase();
        try (var context = boot(url)) {
            String hash = context.getBean(PasswordEncoder.class).encode("original-password");
            try (Connection admin = connect(url, "postgres")) {
                execute(admin, "INSERT INTO tenants(name,subdomain) VALUES ('A','a')");
                try (var statement = admin.prepareStatement("INSERT INTO users(tenant_id,email,password_hash,role) VALUES (1,'admin@example.test',?,'admin')")) {
                    statement.setString(1, hash);
                    statement.executeUpdate();
                }
            }
            AuthService auth = context.getBean(AuthService.class);
            LoginRequest request = new LoginRequest();
            request.setEmail("admin@example.test");
            request.setPassword("original-password");
            assertNotNull(auth.login(request));
            assertNull(TenantContext.getCurrentRole());
            auth.forgotPassword(request.getEmail());
            String token;
            try (Connection admin = connect(url, "postgres"); var statement = admin.createStatement();
                 var result = statement.executeQuery("SELECT token FROM password_reset_tokens")) {
                assertTrue(result.next());
                token = result.getString(1);
            }
            auth.resetPassword(token, "new-password");
            assertThrows(RuntimeException.class, () -> auth.resetPassword(token, "reused-token"));
            request.setPassword("new-password");
            assertNotNull(auth.login(request));
            String superToken = context.getBean(JwtTokenProvider.class).generateToken(99L, "SUPER_ADMIN", null);
            assertNull(context.getBean(JwtTokenProvider.class).getTenantId(superToken));
        }
    }

    @Test
    void failedLoginAttemptsPersistedDespiteLoginException() throws Exception {
        String url = newDatabase();
        try (var context = boot(url)) {
            String hash = context.getBean(PasswordEncoder.class).encode("correct-password");
            try (Connection admin = connect(url, "postgres")) {
                execute(admin, "INSERT INTO tenants(name,subdomain) VALUES ('Store','store')");
                try (var statement = admin.prepareStatement(
                        "INSERT INTO users(tenant_id,email,password_hash,role,failed_login_attempts) VALUES (1,'user@store.test',?,'admin',0)")) {
                    statement.setString(1, hash);
                    statement.executeUpdate();
                }
            }
            AuthService auth = context.getBean(AuthService.class);
            LoginRequest wrongRequest = new LoginRequest();
            wrongRequest.setEmail("user@store.test");
            wrongRequest.setPassword("wrong-password");

            // Lần 1: Nhập sai mật khẩu -> ném BusinessException
            assertThrows(BusinessException.class, () -> auth.login(wrongRequest));

            // Kiểm tra DB: failed_login_attempts vẫn được commit là 1 dù login() bị rollback
            try (Connection admin = connect(url, "postgres")) {
                assertEquals(1, count(admin, "SELECT failed_login_attempts FROM users WHERE email='user@store.test'"));
            }

            // Nhập sai thêm 4 lần (tổng cộng 5 lần)
            for (int i = 2; i <= 5; i++) {
                assertThrows(BusinessException.class, () -> auth.login(wrongRequest));
            }

            // Kiểm tra DB: đạt 5 lần và locked_until đã được set
            try (Connection admin = connect(url, "postgres")) {
                assertEquals(5, count(admin, "SELECT failed_login_attempts FROM users WHERE email='user@store.test'"));
                assertEquals(1, count(admin, "SELECT count(*) FROM users WHERE email='user@store.test' AND locked_until > now()"));
            }

            // Lần 6: Tài khoản bị khóa -> ném BusinessException ACCOUNT_TEMP_LOCKED
            var ex = assertThrows(BusinessException.class, () -> auth.login(wrongRequest));
            assertEquals("ACCOUNT_TEMP_LOCKED", ex.getErrorCode());
        }
    }

    @Test
    void tenantLookupWithSubdomainWorksUnderRlsAndChecksStatus() throws Exception {
        String url = newDatabase();
        try (var context = boot(url)) {
            String hash = context.getBean(PasswordEncoder.class).encode("password123");
            try (Connection admin = connect(url, "postgres")) {
                execute(admin, "INSERT INTO tenants(name,subdomain,status) VALUES ('Shop Sub','shopsub','active')");
                try (var statement = admin.prepareStatement(
                        "INSERT INTO users(tenant_id,email,password_hash,role) VALUES (1,'owner@shopsub.test',?,'admin')")) {
                    statement.setString(1, hash);
                    statement.executeUpdate();
                }
            }
            AuthService auth = context.getBean(AuthService.class);
            LoginRequest request = new LoginRequest();
            request.setEmail("owner@shopsub.test");
            request.setPassword("password123");
            request.setSubdomain("shopsub");

            // Đăng nhập thành công với subdomain mà không bị RLS chặn
            var response = auth.login(request);
            assertNotNull(response);
            assertEquals("shopsub", response.getUserInfo().getSubdomain());

            // Khóa tenant trong database
            try (Connection admin = connect(url, "postgres")) {
                execute(admin, "UPDATE tenants SET status='locked', locked_reason='Overdue billing' WHERE subdomain='shopsub'");
            }

            // Đăng nhập lại với subdomain -> Phát hiện tenant bị khóa và throw TENANT_LOCKED
            var ex = assertThrows(BusinessException.class, () -> auth.login(request));
            assertEquals("TENANT_LOCKED", ex.getErrorCode());
        }
    }

    @Test
    void backgroundWorkersHaveOnlyTheirExplicitCapabilities() throws Exception {
        String url = newDatabase();
        flyway(url).migrate();
        try (Connection admin = connect(url, "postgres")) {
            execute(admin, "INSERT INTO tenants(name,subdomain) VALUES ('A','a')");
            execute(admin, "INSERT INTO products(tenant_id,name) VALUES (1,'A')");
            execute(admin, "INSERT INTO product_skus(tenant_id,product_id,sku_code) VALUES (1,1,'SKU')");
            execute(admin, "INSERT INTO inventory_outbox_events(tenant_id,sku_id,status) VALUES (1,1,'pending')");
        }
        try (Connection app = connect(url, "smartomni_app")) {
            app.setAutoCommit(false);
            RlsSession.set(app, "app.user_role", "outbox_worker");
            assertEquals(1, count(app, "SELECT count(*) FROM inventory_outbox_events"));
            assertEquals(0, count(app, "SELECT count(*) FROM products"));
            execute(app, "UPDATE inventory_outbox_events SET status='sent'");
            app.commit();
            assertEquals(0, count(app, "SELECT count(*) FROM inventory_outbox_events"));
            app.rollback();
        }
    }

    @Test
    void loyaltyClaimsEarnFromOrderTotalAndRewardsDoNotSpendPoints() throws Exception {
        String url = newDatabase();
        flyway(url).migrate();
        try (Connection admin = connect(url, "postgres")) {
            execute(admin, "INSERT INTO tenants(name,subdomain) VALUES ('A','a'),('B','b')");
            execute(admin, "INSERT INTO marketplace_connections(tenant_id,platform,shop_id) VALUES (1,'shopee','shop-a'),(1,'storefront','web')");
            execute(admin, "INSERT INTO customer_accounts(tenant_id,email,password_hash) VALUES (1,'one@example.test','test-hash'),(2,'two@example.test','test-hash')");
            execute(admin, "INSERT INTO users(tenant_id,email,password_hash,role) VALUES (1,'staff@example.test','test-hash','manager')");
            execute(admin, "INSERT INTO orders(tenant_id,connection_id,platform,platform_order_id,status,total_amount,completed_at,eligible_for_points_at) VALUES "
                    + "(1,1,'shopee','pending','pending',500999,now()-interval '10 days',now()-interval '1 day'),"
                    + "(1,1,'shopee','too-soon','completed',500999,now()-interval '1 day',now()+interval '1 day'),"
                    + "(1,1,'shopee','eligible','completed',500999,now()-interval '10 days',now()-interval '1 day'),"
                    + "(1,1,'shopee','other','completed',500999,now()-interval '10 days',now()-interval '1 day')");
            execute(admin, "INSERT INTO loyalty_reward_campaigns(tenant_id,connection_id,created_by,name,discount_percent,required_points,quantity,provisioning_mode,status,starts_at,ends_at) VALUES "
                    + "(1,1,1,'Silver 10%',10,500,1,'import','active',now()-interval '1 day',now()+interval '30 days')");
            assertThrows(Exception.class, () -> execute(admin,
                    "INSERT INTO loyalty_voucher_codes(tenant_id,connection_id,code,points_cost,expires_at) VALUES (1,2,'WEB',10,now()+interval '1 day')"));
        }
        try (Connection app = connect(url, "smartomni_app")) {
            app.setAutoCommit(false);
            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            assertThrows(Exception.class, () -> execute(app,
                    "INSERT INTO loyalty_claims(tenant_id,order_id,customer_id,points_awarded) VALUES (1,1,1,100)"));
            app.rollback();

            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            assertThrows(Exception.class, () -> execute(app,
                    "INSERT INTO loyalty_claims(tenant_id,order_id,customer_id,points_awarded) VALUES (1,2,1,100)"));
            app.rollback();

            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            execute(app, "INSERT INTO loyalty_claims(tenant_id,order_id,customer_id,points_awarded) VALUES (1,3,1,1)");
            assertEquals(500, count(app, "SELECT total_points FROM loyalty_point_accounts WHERE customer_id=1"));
            assertEquals(500, count(app, "SELECT points_awarded FROM loyalty_claims WHERE order_id=3"));
            assertEquals(1, count(app, "SELECT count(*) FROM loyalty_point_accounts WHERE customer_id=1 AND tier='silver'"));
            assertEquals(1, count(app, "SELECT count(*) FROM loyalty_point_ledger WHERE reason='earn'"));
            app.commit();

            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            assertThrows(Exception.class, () -> execute(app,
                    "INSERT INTO loyalty_claims(tenant_id,order_id,customer_id,points_awarded) VALUES (1,3,1,100)"));
            app.rollback();

            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            assertThrows(Exception.class, () -> execute(app,
                    "INSERT INTO loyalty_claims(tenant_id,order_id,customer_id,points_awarded) VALUES (1,4,2,100)"));
            app.rollback();

            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            execute(app, "INSERT INTO loyalty_reward_requests(tenant_id,campaign_id,customer_id) VALUES (1,1,1)");
            assertEquals(500, count(app, "SELECT total_points FROM loyalty_point_accounts WHERE customer_id=1"));
            assertEquals(1, count(app, "SELECT reserved_count FROM loyalty_reward_campaigns WHERE id=1"));
            app.commit();

            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            assertThrows(Exception.class, () -> execute(app,
                    "INSERT INTO loyalty_reward_requests(tenant_id,campaign_id,customer_id) VALUES (1,1,2)"));
            app.rollback();

            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            execute(app, "UPDATE loyalty_reward_requests SET status='issued',code='SHOP-10',issued_at=now() WHERE tenant_id=1 AND id=1");
            assertEquals(500, count(app, "SELECT total_points FROM loyalty_point_accounts WHERE customer_id=1"));
            assertEquals(1, count(app, "SELECT count(*) FROM loyalty_reward_requests WHERE status='issued' AND code='SHOP-10'"));
            assertEquals(0, count(app, "SELECT count(*) FROM customer_accounts WHERE tenant_id=2"));
            long claimId = count(app, "SELECT id FROM loyalty_claims WHERE order_id=3");
            execute(app, "INSERT INTO loyalty_point_ledger(tenant_id,customer_id,claim_id,reason,points_delta) VALUES (1,1," + claimId + ",'reverse',-500)");
            assertEquals(0, count(app, "SELECT total_points FROM loyalty_point_accounts WHERE customer_id=1"));
            assertEquals(1, count(app, "SELECT count(*) FROM loyalty_point_accounts WHERE customer_id=1 AND tier='standard'"));
            app.commit();
            RlsSession.set(app, "app.tenant_id", "1");
            RlsSession.set(app, "app.user_role", "manager");
            assertThrows(Exception.class, () -> execute(app,
                    "INSERT INTO loyalty_point_ledger(tenant_id,customer_id,claim_id,reason,points_delta) VALUES (1,1," + claimId + ",'reverse',-500)"));
            app.rollback();
        }
    }

    @Test
    void databaseWithUnmanagedTablesIsRejectedInsteadOfAutomaticallyBaselined() throws Exception {
        String url = newDatabase();
        try (Connection migration = connect(url, "smartomni_migrator")) {
            execute(migration, "CREATE TABLE existing_data (id bigint)");
        }
        assertThrows(FlywayException.class, () -> flyway(url).migrate());
        assertThrows(RuntimeException.class, () -> boot(url));
    }

    @Test
    void failedMigrationRollsBackItsDdlAndKeepsExistingData() throws Exception {
        String url = newDatabase();
        flyway(url).migrate();
        Path location = Files.createTempDirectory("failed-migration-");
        Files.writeString(location.resolve("V99__broken.sql"), "CREATE TABLE should_rollback(id bigint); SELECT nonexistent_function();");
        Flyway invalid = Flyway.configure().dataSource(url, "smartomni_migrator", TEST_PASSWORD)
                .locations("classpath:db/migration", "filesystem:" + location).load();
        assertThrows(FlywayException.class, invalid::migrate);
        try (Connection admin = connect(url, "postgres")) {
            assertEquals(0, count(admin, "SELECT count(*) FROM pg_tables WHERE tablename='should_rollback'"));
            assertEquals(15, count(admin, "SELECT count(*) FROM flyway_schema_history WHERE success"));
        }
    }

    @Test
    void originalSqlSchemaCanBeUpgradedAndExplicitlyBaselinedWithoutLosingData() throws Exception {
        String url = newDatabase();
        try (Connection admin = connect(url, "postgres")) {
            execute(admin, resource("db/migration/" + MIGRATIONS.get(0)));
            execute(admin, resource("legacy/02_tables.sql"));
            execute(admin, resource("db/migration/" + MIGRATIONS.get(2)));
            execute(admin, resource("db/migration/" + MIGRATIONS.get(3))
                    .replace("RETURNS bigint", "RETURNS integer").replace(")::bigint", ")::integer"));
            execute(admin, resource("db/migration/" + MIGRATIONS.get(4)));
            execute(admin, "INSERT INTO tenants(name,subdomain) VALUES ('Legacy','legacy')");
            execute(admin, "INSERT INTO products(tenant_id,name) VALUES (1,'Keep this product')");
            execute(admin, "INSERT INTO product_skus(tenant_id,product_id,sku_code) VALUES (1,1,'KEEP')");
            execute(admin, Files.readString(Path.of("..", "scripts", "db", "upgrade-legacy-schema.sql")));
            execute(admin, Files.readString(Path.of("..", "scripts", "db", "verify-schema.sql")));
            assertEquals(1, count(admin, "SELECT count(*) FROM products WHERE name='Keep this product'"));
        }
        Flyway baseline = Flyway.configure().dataSource(url, "smartomni_migrator", TEST_PASSWORD)
                .defaultSchema("public").baselineVersion("5").load();
        baseline.baseline();
        assertEquals(10, flyway(url).migrate().migrationsExecuted);
        try (var context = boot(url)) {
            assertNotNull(context.getBean(EntityManagerFactory.class));
        }
        try (Connection admin = connect(url, "postgres")) {
            assertEquals(1, count(admin, "SELECT count(*) FROM product_skus WHERE sku_code='KEEP'"));
            execute(admin, "INSERT INTO tenants(name,subdomain) VALUES ('After','after')");
            assertEquals(2, count(admin, "SELECT max(id) FROM tenants"));
        }
    }

    @Test
    void editingAnAppliedMigrationIsRejectedByChecksumValidation() throws Exception {
        String url = newDatabase();
        flyway(url).migrate();
        Path directory = Files.createTempDirectory("migration-checksum-");
        Path script = directory.resolve("V99__add_marker.sql");
        Files.writeString(script, "CREATE TABLE migration_marker(id bigint);");
        Flyway extended = Flyway.configure().dataSource(url, "smartomni_migrator", TEST_PASSWORD)
                .locations("classpath:db/migration", "filesystem:" + directory).load();
        assertEquals(1, extended.migrate().migrationsExecuted);
        Files.writeString(script, "CREATE TABLE migration_marker(id bigint, changed boolean);");
        assertThrows(FlywayException.class, extended::validate);
    }

    @Test
    void legacyUpgradeRefusesNullTenantDataAndRollsBackAllChanges() throws Exception {
        String url = newDatabase();
        try (Connection admin = connect(url, "postgres")) {
            execute(admin, resource("db/migration/" + MIGRATIONS.get(0)));
            execute(admin, resource("legacy/02_tables.sql"));
            execute(admin, resource("db/migration/" + MIGRATIONS.get(2)));
            execute(admin, resource("db/migration/" + MIGRATIONS.get(3))
                    .replace("RETURNS bigint", "RETURNS integer").replace(")::bigint", ")::integer"));
            execute(admin, resource("db/migration/" + MIGRATIONS.get(4)));
            execute(admin, "INSERT INTO products(tenant_id,name) VALUES (NULL,'Needs reconciliation')");
            String upgrade = Files.readString(Path.of("..", "scripts", "db", "upgrade-legacy-schema.sql"));
            assertThrows(Exception.class, () -> execute(admin, upgrade));
            execute(admin, "ROLLBACK");
            assertEquals(1, count(admin, "SELECT count(*) FROM products WHERE tenant_id IS NULL"));
            assertEquals(1, count(admin, "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='products' AND column_name='id' AND data_type='integer'"));
        }
    }
}
