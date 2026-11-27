package com.smartomni.migrations;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.dto.loyalty.ClaimPointsRequest;
import com.smartomni.order.dto.loyalty.CreateCampaignRequest;
import com.smartomni.order.dto.loyalty.CreateRewardRequest;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherResponse;
import com.smartomni.order.dto.loyalty.LoyaltyCampaignDto;
import com.smartomni.order.dto.loyalty.LoyaltyClaimDto;
import com.smartomni.order.dto.loyalty.LoyaltyProfileDto;
import com.smartomni.order.dto.loyalty.LoyaltyRewardRequestDto;
import com.smartomni.order.service.loyalty.IntegrationVoucherClient;
import com.smartomni.order.service.loyalty.LoyaltyCampaignService;
import com.smartomni.order.service.loyalty.LoyaltyCustomerService;
import com.smartomni.order.service.loyalty.LoyaltyRewardService;
import com.smartomni.order.service.loyalty.LoyaltyVoucherWorker;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end PostgreSQL 16 + Flyway (V1..V23) + RLS + Trigger verification for WF-02 loyalty.
 */
class LoyaltyDatabaseIntegrationTest {

    private static EmbeddedPostgres postgres;
    private static final String TEST_PASSWORD = "temporary-test-password";

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            exec(c, "CREATE ROLE smartomni_migrator LOGIN PASSWORD '" + TEST_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
            exec(c, "CREATE ROLE smartomni_app LOGIN PASSWORD '" + TEST_PASSWORD
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

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void fullWf02FlowOnPostgres16WithRlsAndTriggers() throws Exception {
        String dbName = "loyalty_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            exec(c, "CREATE DATABASE " + dbName);
            exec(c, "GRANT ALL PRIVILEGES ON DATABASE " + dbName + " TO smartomni_migrator");
            exec(c, "ALTER DATABASE " + dbName + " OWNER TO smartomni_migrator");
        }

        String jdbcUrl = postgres.getJdbcUrl("postgres", dbName);
        Flyway.configure()
                .dataSource(jdbcUrl, "smartomni_migrator", TEST_PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection superConn = postgres.getDatabase("postgres", dbName).getConnection()) {
            // Confirm V23 removed customer_accounts and temporary V22 cron tables
            assertFalse(tableExists(superConn, "customer_accounts"));
            assertFalse(tableExists(superConn, "order_loyalty_ledger"));
            assertFalse(tableExists(superConn, "order_loyalty_balances"));
            assertTrue(tableExists(superConn, "loyalty_notification_events"));

            long tenantA = insertTenant(superConn, "tenanta");
            long tenantB = insertTenant(superConn, "tenantb");

            long adminA = insertUser(superConn, tenantA, "admin@a.test", "admin");
            long customerA = insertUser(superConn, tenantA, "cust@a.test", "customer");
            long customerB = insertUser(superConn, tenantB, "cust@b.test", "customer");

            long connA = insertConnection(superConn, tenantA, "tiktok_shop", "connected");

            long eligibleOrder = insertOrder(superConn, tenantA, "ORD-ELIG-1", "completed",
                    BigDecimal.valueOf(550000), Instant.now().minus(16, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
            long earlyOrder = insertOrder(superConn, tenantA, "ORD-EARLY-1", "completed",
                    BigDecimal.valueOf(300000), Instant.now().minus(5, ChronoUnit.DAYS), Instant.now().plus(10, ChronoUnit.DAYS));
            long returnedOrder = insertOrder(superConn, tenantA, "ORD-RET-1", "completed",
                    BigDecimal.valueOf(200000), Instant.now().minus(20, ChronoUnit.DAYS), Instant.now().minus(5, ChronoUnit.DAYS));
            insertApprovedReturn(superConn, tenantA, returnedOrder);

            try (Connection appConn = DriverManager.getConnection(jdbcUrl, "smartomni_app", TEST_PASSWORD)) {
                SingleConnectionDataSource ds = new SingleConnectionDataSource(appConn, true);
                JdbcTemplate jdbc = new JdbcTemplate(ds);
                TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

                IntegrationVoucherClient voucherClient = mock(IntegrationVoucherClient.class);
                LoyaltyVoucherWorker worker = new LoyaltyVoucherWorker(jdbc, tx, voucherClient);
                LoyaltyCustomerService customerService = new LoyaltyCustomerService(jdbc);
                LoyaltyCampaignService campaignService = new LoyaltyCampaignService(jdbc);
                LoyaltyRewardService rewardService = new LoyaltyRewardService(jdbc, worker);

                // Act as customerA in tenantA
                setRls(appConn, tenantA, "customer");
                LoyaltyClaimDto claim1 = customerService.claimOrderPoints(customerA, tenantA,
                        new ClaimPointsRequest(eligibleOrder, BigDecimal.valueOf(550000)));
                assertEquals(550L, claim1.getPointsAwarded());

                // Duplicate claim in same tenant -> 409 CONFLICT
                BusinessException dup = assertThrows(BusinessException.class,
                        () -> customerService.claimOrderPoints(customerA, tenantA,
                                new ClaimPointsRequest(eligibleOrder, BigDecimal.valueOf(550000))));
                assertEquals("ORDER_ALREADY_CLAIMED", dup.getErrorCode());

                // Early claim (< 15 days) -> rejected
                BusinessException early = assertThrows(BusinessException.class,
                        () -> customerService.claimOrderPoints(customerA, tenantA,
                                new ClaimPointsRequest(earlyOrder, BigDecimal.valueOf(300000))));
                assertEquals("ORDER_NOT_ELIGIBLE_YET", early.getErrorCode());

                // Order with approved return -> rejected
                BusinessException ret = assertThrows(BusinessException.class,
                        () -> customerService.claimOrderPoints(customerA, tenantA,
                                new ClaimPointsRequest(returnedOrder, BigDecimal.valueOf(200000))));
                assertEquals("ORDER_HAS_APPROVED_RETURN", ret.getErrorCode());

                // Cross-tenant customerB cannot claim tenantA order
                setRls(appConn, tenantB, "customer");
                BusinessException cross = assertThrows(BusinessException.class,
                        () -> customerService.claimOrderPoints(customerB, tenantB,
                                new ClaimPointsRequest(eligibleOrder, BigDecimal.valueOf(550000))));
                assertEquals("ORDER_NOT_FOUND", cross.getErrorCode());

                // Verify profile & Gold tier (550 points >= 500)
                setRls(appConn, tenantA, "customer");
                LoyaltyProfileDto profile = customerService.getMyProfile(customerA, tenantA);
                assertEquals(550L, profile.getTotalPoints());
                assertEquals("Gold", profile.getTier());
                assertEquals("Platinum", profile.getNextTier());
                assertEquals(450L, profile.getPointsToNextTier());

                // Admin creates campaign (quantity=1, required_points=500)
                setRls(appConn, tenantA, "admin");
                LoyaltyCampaignDto campaign = campaignService.createCampaign(adminA, tenantA,
                        CreateCampaignRequest.builder()
                                .connectionId(connA)
                                .name("Silver 15% Off")
                                .discountPercent(BigDecimal.valueOf(15))
                                .quantity(1)
                                .requiredPoints(500L)
                                .provisioningMode("import")
                                .startsAt(Instant.now().minus(1, ChronoUnit.HOURS))
                                .endsAt(Instant.now().plus(7, ChronoUnit.DAYS))
                                .build());

                // Customer requests reward -> FAILED from marketplace releases reserved slot
                setRls(appConn, tenantA, "customer");
                when(voucherClient.issueVoucher(eq(tenantA), any()))
                        .thenReturn(IntegrationIssueVoucherResponse.builder()
                                .outcome("FAILED")
                                .failureReason("Shop coupon not found")
                                .build());

                LoyaltyRewardRequestDto req1 = rewardService.requestReward(customerA, tenantA,
                        new CreateRewardRequest(campaign.getId(), UUID.randomUUID().toString()));
                assertEquals("pending", req1.getStatus());

                // Wait/run worker synchronously for deterministic assertion
                setRls(appConn, tenantA, "admin");
                worker.processPendingRequest(tenantA, req1.getId());

                // Check slot was released (reserved_count == 0)
                Integer reservedAfterFail = jdbc.queryForObject(
                        "SELECT reserved_count FROM loyalty_reward_campaigns WHERE tenant_id = ? AND id = ?",
                        Integer.class, tenantA, campaign.getId());
                assertEquals(0, reservedAfterFail);

                // Customer retries with new key -> ISSUED from marketplace
                setRls(appConn, tenantA, "customer");
                when(voucherClient.issueVoucher(eq(tenantA), any()))
                        .thenReturn(IntegrationIssueVoucherResponse.builder()
                                .outcome("ISSUED")
                                .externalVoucherId("TT-VCH-999")
                                .code("SILVERSALE15")
                                .build());

                LoyaltyRewardRequestDto req2 = rewardService.requestReward(customerA, tenantA,
                        new CreateRewardRequest(campaign.getId(), UUID.randomUUID().toString()));
                setRls(appConn, tenantA, "admin");
                worker.processPendingRequest(tenantA, req2.getId());

                // Verify issued code, notification event, and cumulative points untouched (still 550)
                setRls(appConn, tenantA, "customer");
                var vouchers = rewardService.listMyVouchers(customerA, tenantA);
                assertTrue(vouchers.stream().anyMatch(v ->
                        "issued".equals(v.getStatus()) && "SILVERSALE15".equals(v.getCode())));
                assertEquals(550L, customerService.getMyProfile(customerA, tenantA).getTotalPoints());

                setRls(appConn, tenantA, "admin");
                Integer notifCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM loyalty_notification_events WHERE tenant_id = ? AND reward_request_id = ?",
                        Integer.class, tenantA, req2.getId());
                assertEquals(1, notifCount);
            }
        }
    }

    /** Claims by the marketplace order id the shopper sees, tier setup per shop, and the per-platform progress. */
    @Test
    void marketplaceOrderIdClaimsAndPerShopTiersOnPostgres() throws Exception {
        String dbName = "loyalty_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = postgres.getPostgresDatabase().getConnection()) {
            exec(c, "CREATE DATABASE " + dbName);
            exec(c, "ALTER DATABASE " + dbName + " OWNER TO smartomni_migrator");
        }
        String jdbcUrl = postgres.getJdbcUrl("postgres", dbName);
        Flyway.configure().dataSource(jdbcUrl, "smartomni_migrator", TEST_PASSWORD)
                .locations("classpath:db/migration").load().migrate();

        try (Connection superConn = postgres.getDatabase("postgres", dbName).getConnection()) {
            long tenantA = insertTenant(superConn, "shopa");
            long tenantB = insertTenant(superConn, "shopb");
            long adminA = insertUser(superConn, tenantA, "admin@a.test", "tenant_admin");
            long buyer = insertUser(superConn, tenantA, "buyer@a.test", "customer");
            long otherBuyer = insertUser(superConn, tenantA, "other@a.test", "customer");
            long buyerB = insertUser(superConn, tenantB, "buyer@b.test", "customer");
            long tiktokA = insertConnection(superConn, tenantA, "tiktok_shop", "connected");
            long shopeeA = insertConnection(superConn, tenantA, "shopee", "connected");

            insertOrder(superConn, tenantA, "5760028171077135663", "completed", BigDecimal.valueOf(184000),
                    Instant.now().minus(16, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
            insertOrder(superConn, tenantA, "5760000000000000001", "completed", BigDecimal.valueOf(343000),
                    Instant.now().minus(3, ChronoUnit.DAYS), Instant.now().plus(12, ChronoUnit.DAYS));
            insertOrder(superConn, tenantA, "SAME-ID", "completed", BigDecimal.valueOf(500000),
                    Instant.now().minus(20, ChronoUnit.DAYS), Instant.now().minus(5, ChronoUnit.DAYS));
            exec(superConn, "SET LOCAL app.user_role = 'super_admin'");
            exec(superConn, "INSERT INTO orders (tenant_id, platform, platform_order_id, status, total_amount, completed_at, "
                    + "eligible_for_points_at) VALUES (" + tenantA + ", 'shopee', 'SAME-ID', 'completed', 900000, "
                    + "now() - interval '20 days', now() - interval '5 days')");

            try (Connection appConn = DriverManager.getConnection(jdbcUrl, "smartomni_app", TEST_PASSWORD)) {
                SingleConnectionDataSource ds = new SingleConnectionDataSource(appConn, true);
                JdbcTemplate jdbc = new JdbcTemplate(ds);
                LoyaltyCustomerService customers = new LoyaltyCustomerService(jdbc);
                LoyaltyCampaignService campaigns = new LoyaltyCampaignService(jdbc);
                // TikTok tiers are checked against the shop's Seller Center code batches (read through the integration service).
                IntegrationVoucherClient batchClient = mock(IntegrationVoucherClient.class);
                when(batchClient.listTiktokBatches(tenantA, tiktokA)).thenReturn(List.of(
                        batch("Loyalty Gold 10%", 10), batch("Loyalty Gold 12%", 12),
                        batch("Loyalty Platinum 20%", 20), batch("Loyalty Diamond 30%", 30)));
                com.smartomni.order.service.loyalty.LoyaltyTierService tiers =
                        new com.smartomni.order.service.loyalty.LoyaltyTierService(jdbc, campaigns, batchClient);

                // Any customer may claim with the marketplace id; points come from the stored order total.
                setRls(appConn, tenantA, "customer");
                LoyaltyClaimDto claim = customers.claimOrderPoints(buyer, tenantA,
                        ClaimPointsRequest.builder().platformOrderId(" 5760028171077135663 ").build());
                assertEquals(184L, claim.getPointsAwarded());

                BusinessException again = assertThrows(BusinessException.class, () -> customers.claimOrderPoints(otherBuyer,
                        tenantA, ClaimPointsRequest.builder().platformOrderId("5760028171077135663").build()));
                assertEquals("ORDER_ALREADY_CLAIMED", again.getErrorCode());

                BusinessException early = assertThrows(BusinessException.class, () -> customers.claimOrderPoints(buyer,
                        tenantA, ClaimPointsRequest.builder().platformOrderId("5760000000000000001").build()));
                assertEquals("ORDER_NOT_ELIGIBLE_YET", early.getErrorCode());
                assertTrue(early.getMessage().contains("eligible from"), early.getMessage());

                BusinessException ambiguous = assertThrows(BusinessException.class, () -> customers.claimOrderPoints(buyer,
                        tenantA, ClaimPointsRequest.builder().platformOrderId("SAME-ID").build()));
                assertEquals("PLATFORM_REQUIRED", ambiguous.getErrorCode());
                assertEquals(900L, customers.claimOrderPoints(buyer, tenantA,
                        ClaimPointsRequest.builder().platformOrderId("SAME-ID").platform("shopee").build()).getPointsAwarded());

                setRls(appConn, tenantB, "customer");
                BusinessException cross = assertThrows(BusinessException.class, () -> customers.claimOrderPoints(buyerB,
                        tenantB, ClaimPointsRequest.builder().platformOrderId("SAME-ID").platform("tiktok_shop").build()));
                assertEquals("ORDER_NOT_FOUND", cross.getErrorCode());

                // Shop sets up tiers per marketplace: the suggestion on TikTok, its own milestones on Shopee.
                setRls(appConn, tenantA, "tenant_admin");
                var tiktokSetup = tiers.saveTiers(adminA, tenantA, com.smartomni.order.dto.loyalty.SaveLoyaltyTiersRequest.builder()
                        .connectionId(tiktokA).tiers(com.smartomni.order.service.loyalty.LoyaltyTierService.SUGGESTED.stream()
                                .map(t -> t.toBuilder().tiktokBatchTitle("Loyalty " + t.getName() + " "
                                        + t.getDiscountPercent().intValue() + "%").build()).toList()).build());
                // each TikTok tier takes the free codes and the end of its batch
                assertEquals(List.of(40), jdbc.queryForList("SELECT DISTINCT quantity FROM loyalty_reward_campaigns "
                        + "WHERE tenant_id = ? AND connection_id = ?", Integer.class, tenantA, tiktokA));
                assertEquals(List.of("Gold", "Platinum", "Diamond"),
                        tiktokSetup.getTiers().stream().map(com.smartomni.order.dto.loyalty.LoyaltyTierSetupDto.Tier::getName).toList());
                assertEquals("import", jdbc.queryForObject("SELECT DISTINCT provisioning_mode FROM loyalty_reward_campaigns "
                        + "WHERE tenant_id = ? AND connection_id = ?", String.class, tenantA, tiktokA));

                tiers.saveTiers(adminA, tenantA, com.smartomni.order.dto.loyalty.SaveLoyaltyTiersRequest.builder()
                        .connectionId(shopeeA).tiers(List.of(
                                tier(null, "Bạc", 300, 5), tier(null, "Vàng", 2000, 25))).build());
                // A draft campaign made in the Campaigns panel is not a tier the editor shows: saving tiers leaves it alone.
                long draft = campaigns.createCampaign(adminA, tenantA, CreateCampaignRequest.builder()
                        .connectionId(tiktokA).name("Draft promo").discountPercent(BigDecimal.TEN).quantity(5)
                        .requiredPoints(50L).provisioningMode("import").status("draft")
                        .startsAt(Instant.now()).endsAt(Instant.now().plus(7, ChronoUnit.DAYS)).build()).getId();
                // Re-saving TikTok: rename Gold and drop Diamond -> Diamond is closed, Gold keeps its campaign id.
                var gold = tiktokSetup.getTiers().get(0);
                var platinum = tiktokSetup.getTiers().get(1);
                tiers.saveTiers(adminA, tenantA, com.smartomni.order.dto.loyalty.SaveLoyaltyTiersRequest.builder()
                        .connectionId(tiktokA).tiers(List.of(
                                tier(gold.getCampaignId(), "Gold+", 500, 12).toBuilder().tiktokBatchTitle("Loyalty Gold 12%").build(),
                                tier(platinum.getCampaignId(), "Platinum", 1000, 20).toBuilder()
                                        .tiktokBatchTitle("Loyalty Platinum 20%").build())).build());
                assertEquals("Loyalty Gold 12%", jdbc.queryForObject("SELECT tiktok_batch_title FROM loyalty_reward_campaigns "
                        + "WHERE tenant_id = ? AND id = ?", String.class, tenantA, gold.getCampaignId()));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM loyalty_reward_campaigns WHERE tenant_id = ? "
                        + "AND connection_id = ? AND status = 'closed'", Integer.class, tenantA, tiktokA));
                assertEquals("draft", jdbc.queryForObject("SELECT status FROM loyalty_reward_campaigns WHERE tenant_id = ? AND id = ?",
                        String.class, tenantA, draft));

                // Profile: one wallet (184 + 900 = 1084), progress per marketplace.
                setRls(appConn, tenantA, "customer");
                LoyaltyProfileDto profile = customers.getMyProfile(buyer, tenantA);
                assertEquals(1084L, profile.getTotalPoints());
                assertEquals(2, profile.getPlatforms().size());
                var tiktok = profile.getPlatforms().stream().filter(p -> "tiktok_shop".equals(p.getPlatform())).findFirst().orElseThrow();
                assertEquals("Platinum", tiktok.getCurrentTier());
                assertNull(tiktok.getNextTier());
                assertEquals(List.of("Gold+", "Platinum"), tiktok.getMilestones().stream()
                        .map(LoyaltyProfileDto.Milestone::getName).toList());
                assertTrue(tiktok.getMilestones().stream().allMatch(LoyaltyProfileDto.Milestone::isReached));
                var shopee = profile.getPlatforms().stream().filter(p -> "shopee".equals(p.getPlatform())).findFirst().orElseThrow();
                assertEquals("Bạc", shopee.getCurrentTier());
                assertEquals("Vàng", shopee.getNextTier());
                assertEquals(916L, shopee.getPointsToNextTier());
                assertEquals("Platinum", profile.getTier(), "summary tier is the highest milestone reached on any shop");
                assertEquals("Vàng", profile.getNextTier());
            }
        }
    }

    private static com.smartomni.order.dto.loyalty.LoyaltyTierSetupDto.Tier tier(Long campaignId, String name, long points, int percent) {
        return com.smartomni.order.dto.loyalty.LoyaltyTierSetupDto.Tier.builder().campaignId(campaignId).name(name)
                .requiredPoints(points).discountPercent(BigDecimal.valueOf(percent)).build();
    }

    private static com.smartomni.order.dto.loyalty.TiktokVoucherBatchDto batch(String title, int percent) {
        return com.smartomni.order.dto.loyalty.TiktokVoucherBatchDto.builder().title(title).discountType("PERCENT_OFF")
                .discountValue(BigDecimal.valueOf(percent)).ongoingCodes(40).freeCodes(40)
                .endTime(Instant.now().plus(30, ChronoUnit.DAYS)).build();
    }

    private static void setRls(Connection conn, long tenantId, String role) throws Exception {
        exec(conn, "SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
        exec(conn, "SELECT set_config('app.user_role', '" + role + "', false)");
    }

    private static boolean tableExists(Connection c, String tableName) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long insertTenant(Connection c, String sub) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenants (name, subdomain) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, "Tenant " + sub);
            ps.setString(2, sub);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertUser(Connection c, long tenantId, String email, String role) throws Exception {
        exec(c, "SET LOCAL app.user_role = 'super_admin'");
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (tenant_id, email, password_hash, full_name, role, status) "
                        + "VALUES (?, ?, 'hash', ?, ?::user_role, 'active') RETURNING id")) {
            ps.setLong(1, tenantId);
            ps.setString(2, email);
            ps.setString(3, email);
            ps.setString(4, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertConnection(Connection c, long tenantId, String platform, String status) throws Exception {
        exec(c, "SET LOCAL app.user_role = 'super_admin'");
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO marketplace_connections (tenant_id, platform, shop_id, shop_name, status) "
                        + "VALUES (?, ?::platform_type, ?, 'Shop Test', ?::connection_status) RETURNING id")) {
            ps.setLong(1, tenantId);
            ps.setString(2, platform);
            ps.setString(3, "SHOP-" + UUID.randomUUID());
            ps.setString(4, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertOrder(Connection c, long tenantId, String extId, String status,
                                    BigDecimal total, Instant completedAt, Instant eligibleAt) throws Exception {
        exec(c, "SET LOCAL app.user_role = 'super_admin'");
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders (tenant_id, platform, platform_order_id, status, total_amount, completed_at, eligible_for_points_at) "
                        + "VALUES (?, 'tiktok_shop'::platform_type, ?, ?::order_status, ?, ?, ?) RETURNING id")) {
            ps.setLong(1, tenantId);
            ps.setString(2, extId);
            ps.setString(3, status);
            ps.setBigDecimal(4, total);
            ps.setTimestamp(5, java.sql.Timestamp.from(completedAt));
            ps.setTimestamp(6, java.sql.Timestamp.from(eligibleAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertApprovedReturn(Connection c, long tenantId, long orderId) throws Exception {
        exec(c, "SET LOCAL app.user_role = 'super_admin'");
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO returns_refunds (tenant_id, order_id, trigger_type, status) "
                        + "VALUES (?, ?, 'customer_request'::return_trigger_type, 'approved'::refund_status)")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
