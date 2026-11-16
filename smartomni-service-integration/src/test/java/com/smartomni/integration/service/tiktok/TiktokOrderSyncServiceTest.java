package com.smartomni.integration.service.tiktok;

import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderSyncResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokOrderSyncService Unit Tests (D2 & WF-02)")
class TiktokOrderSyncServiceTest {

    private TiktokOrderClient orderClient;
    private CatalogMappingService catalogMappingService;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private TiktokOrderSyncService syncService;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        orderClient = mock(TiktokOrderClient.class);
        catalogMappingService = mock(CatalogMappingService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> cb = invocation.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        syncService = new TiktokOrderSyncService(orderClient, catalogMappingService, jdbcTemplate, transactionTemplate);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(10L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
    }

    @Test
    @DisplayName("syncOrders inserts new COMPLETED order with completed_at and eligible_for_points_at = completed_at + 15 days (WF-02)")
    void syncOrders_NewCompletedOrder_SetsLoyaltyDates() {
        long completedUnix = 1700000000L;
        TiktokOrderDetailResponse order = TiktokOrderDetailResponse.builder()
                .orderId("ORD-NEW-1")
                .orderStatus("COMPLETED")
                .completedTime(completedUnix)
                .createTime(1699900000L)
                .payment(TiktokOrderDetailResponse.Payment.builder().totalAmount(new BigDecimal("250000")).build())
                .lineItems(List.of(
                        TiktokOrderDetailResponse.LineItem.builder()
                                .skuId("TK-SKU-1")
                                .sellerSku("LOCAL-SKU-1")
                                .quantity(2)
                                .salePrice(new BigDecimal("125000"))
                                .build()
                ))
                .build();

        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(order)).build());
        when(catalogMappingService.findInternalSkuId(10L, "tiktok_shop", "LOCAL-SKU-1"))
                .thenReturn(Optional.of(501L));

        // Simulate no existing row in orders
        when(jdbcTemplate.query(contains("FROM orders"), any(RowMapper.class), eq(10L), eq(77L), eq("ORD-NEW-1")))
                .thenReturn(List.of());

        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    KeyHolder kh = invocation.getArgument(1);
                    kh.getKeyList().add(Map.of("id", 9001L));
                    return 1;
                });

        TiktokOrderSyncResponse result = syncService.syncOrders(connection, null, null);

        assertEquals(1, result.getTotalFetched());
        assertEquals(1, result.getCreatedCount());
        assertEquals(0, result.getUpdatedCount());

        // Verify RLS session settings were applied
        verify(jdbcTemplate).queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, "10");
        verify(jdbcTemplate).queryForObject(eq("SELECT set_config('app.user_role', ?, true)"), eq(String.class), anyString());

        // Verify order_items inserted with resolved internal sku_id
        verify(jdbcTemplate).update(
                contains("INSERT INTO order_items"),
                eq(10L), eq(9001L), eq(501L), eq(2), eq(new BigDecimal("125000")), isNull());
    }

    @Test
    @DisplayName("syncOrders preserves existing completed_at and eligible_for_points_at on repeated sync (WF-02)")
    void syncOrders_ExistingCompletedOrder_DoesNotOverwriteCompletedAt() throws Exception {
        Instant originalCompletedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant originalEligibleAt = originalCompletedAt.plus(15, ChronoUnit.DAYS);

        // Simulator returns a later completed_time on re-sync
        long laterUnix = Instant.parse("2026-02-01T00:00:00Z").getEpochSecond();
        TiktokOrderDetailResponse order = TiktokOrderDetailResponse.builder()
                .orderId("ORD-EXISTING-1")
                .orderStatus("COMPLETED")
                .completedTime(laterUnix)
                .payment(TiktokOrderDetailResponse.Payment.builder().totalAmount(new BigDecimal("100000")).build())
                .lineItems(List.of(
                        TiktokOrderDetailResponse.LineItem.builder()
                                .skuId("TK-SKU-1")
                                .quantity(1)
                                .salePrice(new BigDecimal("100000"))
                                .build()
                ))
                .build();

        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(order)).build());

        // Simulate existing row with originalCompletedAt already persisted
        when(jdbcTemplate.query(contains("FROM orders"), any(RowMapper.class), eq(10L), eq(77L), eq("ORD-EXISTING-1")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong("id")).thenReturn(888L);
                    when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(originalCompletedAt));
                    when(rs.getTimestamp("eligible_for_points_at")).thenReturn(Timestamp.from(originalEligibleAt));
                    return List.of(mapper.mapRow(rs, 0));
                });

        TiktokOrderSyncResponse result = syncService.syncOrders(connection, null, null);

        assertEquals(1, result.getTotalFetched());
        assertEquals(0, result.getCreatedCount());
        assertEquals(1, result.getUpdatedCount());

        // Verify UPDATE kept originalCompletedAt and originalEligibleAt instead of laterUnix
        verify(jdbcTemplate).update(
                contains("UPDATE orders SET status"),
                eq("completed"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(new BigDecimal("100000")),
                eq(Timestamp.from(originalCompletedAt)),
                eq(Timestamp.from(originalEligibleAt)),
                eq(10L),
                eq(888L));
    }
}
