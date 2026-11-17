package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.controller.TiktokVoucherController;
import com.smartomni.integration.dto.tiktok.CreateTiktokVoucherRequest;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("WF-02 Part A: TikTok Voucher Client, Service & Controller Tests")
class TiktokVoucherControllerAndServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TiktokShopApiExecutor executor;
    private TiktokVoucherClient voucherClient;
    private TiktokTenantConnections tenantConnections;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private TiktokVoucherService voucherService;
    private TiktokVoucherController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        executor = mock(TiktokShopApiExecutor.class);
        voucherClient = new TiktokVoucherClient(executor);
        tenantConnections = mock(TiktokTenantConnections.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        voucherService = new TiktokVoucherService(tenantConnections, voucherClient, jdbcTemplate, transactionTemplate);
        controller = new TiktokVoucherController(voucherService);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(10L);
        connection.setShopId("SHOP-10");
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("POST /api/v1/tiktok/vouchers persists voucher with RLS")
    void createVoucher_Success() {
        Instant start = Instant.parse("2026-11-17T00:00:00Z");
        Instant end = start.plus(15, ChronoUnit.DAYS);

        when(jdbcTemplate.query(contains("FROM tiktok_vouchers WHERE tenant_id = ? AND connection_id = ?"),
                any(RowMapper.class), eq(10L), eq(77L), anyString())).thenReturn(List.of());
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(inv -> {
                    KeyHolder kh = inv.getArgument(1);
                    kh.getKeyList().add(Map.of("id", 5001L));
                    return 1;
                });

        CreateTiktokVoucherRequest req = CreateTiktokVoucherRequest.builder()
                .title("Voucher 15%")
                .discountType("PERCENT_OFF")
                .discountValue(new BigDecimal("15"))
                .maxDiscountAmount(new BigDecimal("50000"))
                .minOrderAmount(new BigDecimal("100000"))
                .usageLimit(100)
                .code("SAVE15")
                .startTime(start)
                .endTime(end)
                .idempotencyKey("idem-1")
                .build();

        try (var scope = TenantContext.openScope(10L, "TENANT_ADMIN", 1L)) {
            ResponseEntity<ApiResponse<TiktokVoucherDto>> res = controller.createVoucher(req);

            assertEquals(HttpStatus.OK, res.getStatusCode());
            assertNotNull(res.getBody());
            TiktokVoucherDto dto = res.getBody().getData();
            assertEquals(5001L, dto.getId());
            assertEquals(10L, dto.getTenantId());
            assertEquals("SAVE15", dto.getVoucherCode());
            assertEquals("PERCENT_OFF", dto.getDiscountType());
            assertEquals(new BigDecimal("15"), dto.getDiscountValue());
        }

        verify(jdbcTemplate).queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, "10");
        verify(jdbcTemplate).queryForObject("SELECT set_config('app.user_role', ?, true)", String.class, "tenant_admin");
    }

    @Test
    @DisplayName("POST /api/v1/tiktok/vouchers/sync reads POST /promotion/202406/coupons/search from Simulator develop")
    void syncCoupons_Success() throws Exception {
        JsonNode simCoupons = mapper.readTree("""
                {
                  "coupons": [
                    {
                      "id": "77001",
                      "title": "Simulator 20k Off",
                      "status": "ONGOING",
                      "promo_code": "SIM20K",
                      "discount": { "type": "AMOUNT_OFF", "reduction_amount": { "amount": "20000", "currency": "VND" } },
                      "threshold": { "type": "MIN_SPEND", "min_spend": { "amount": "100000", "currency": "VND" } },
                      "claim_duration": { "start_time": 1763337600, "end_time": 1765929600 },
                      "usage_limits": { "redemption_limit": 50 }
                    }
                  ]
                }
                """);
        when(executor.call(eq(77L), eq("POST"), eq("/promotion/202406/coupons/search"), eq(Map.of("page_size", "50")), eq("{}")))
                .thenReturn(simCoupons);
        when(jdbcTemplate.query(contains("FROM tiktok_vouchers WHERE tenant_id = ? AND connection_id = ?"),
                any(RowMapper.class), eq(10L), eq(77L), eq("77001"))).thenReturn(List.of());
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(inv -> {
                    KeyHolder kh = inv.getArgument(1);
                    kh.getKeyList().add(Map.of("id", 6001L));
                    return 1;
                });

        ResponseEntity<ApiResponse<List<TiktokVoucherDto>>> res = controller.syncCoupons();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1, res.getBody().getData().size());
        assertEquals("77001", res.getBody().getData().get(0).getTiktokVoucherId());
        assertEquals("SIM20K", res.getBody().getData().get(0).getVoucherCode());
        assertEquals(new BigDecimal("20000"), res.getBody().getData().get(0).getDiscountValue());
    }

    @Test
    @DisplayName("GET /api/v1/tiktok/vouchers lists tenant vouchers with RLS")
    void listVouchers_Success() {
        TiktokVoucherDto v1 = TiktokVoucherDto.builder()
                .id(1L)
                .tenantId(10L)
                .tiktokVoucherId("7700123456")
                .voucherCode("SO8899AABB")
                .title("Voucher 15%")
                .status("ONGOING")
                .build();

        when(jdbcTemplate.query(contains("FROM tiktok_vouchers WHERE tenant_id = ?"), any(RowMapper.class), eq(10L)))
                .thenReturn(List.of(v1));

        ResponseEntity<ApiResponse<List<TiktokVoucherDto>>> res = controller.listVouchers();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1, res.getBody().getData().size());
        assertEquals("SO8899AABB", res.getBody().getData().get(0).getVoucherCode());
        verify(jdbcTemplate).queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, "10");
    }

    @Test
    @DisplayName("createVoucher rejects invalid PERCENT_OFF > 100")
    void createVoucher_InvalidPercent_ThrowsBadRequest() {
        CreateTiktokVoucherRequest req = CreateTiktokVoucherRequest.builder()
                .title("Invalid")
                .discountType("PERCENT_OFF")
                .discountValue(new BigDecimal("150"))
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.createVoucher(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("INVALID_DISCOUNT_VALUE", ex.getErrorCode());
    }
}
