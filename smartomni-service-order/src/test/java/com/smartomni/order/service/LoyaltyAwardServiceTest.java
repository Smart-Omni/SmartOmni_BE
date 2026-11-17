package com.smartomni.order.service;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.controller.InternalOrderJobsController;
import com.smartomni.order.controller.LoyaltyAwardController;
import com.smartomni.order.dto.LoyaltyAwardResultDto;
import com.smartomni.order.scheduler.OrderPollingScheduler;
import com.smartomni.order.service.LoyaltyAwardService.EligibleOrderRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("WF-02 Part B: LoyaltyAwardService & Controllers Tests")
class LoyaltyAwardServiceTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private LoyaltyAwardService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        service = new LoyaltyAwardService(jdbcTemplate, transactionTemplate);
    }

    @Test
    @DisplayName("Awards 1 point per 1,000 VND for eligible completed orders >= 15 days and enforces RLS + idempotency")
    void awardEligiblePointsForTenant_Success() {
        EligibleOrderRow order = new EligibleOrderRow(101L, new BigDecimal("250900"), "0901234567", "Nguyen Van A");

        when(jdbcTemplate.query(contains("FROM orders"), any(RowMapper.class), eq(10L)))
                .thenReturn(List.of(order));
        when(jdbcTemplate.update(contains("UPDATE orders"), eq(250L), eq(10L), eq(101L)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO order_loyalty_ledger"),
                eq(10L), eq(101L), eq("PHONE:0901234567"), eq(250L),
                eq(new BigDecimal("250900")), eq("ORDER_LOYALTY:101")))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO order_loyalty_balances"),
                eq(10L), eq("PHONE:0901234567"), eq(250L)))
                .thenReturn(1);

        LoyaltyAwardResultDto result = service.awardEligiblePointsForTenant(10L, "TENANT_ADMIN");

        assertEquals(1, result.getOrdersEvaluated());
        assertEquals(1, result.getOrdersAwarded());
        assertEquals(250L, result.getTotalPointsAwarded());
        assertEquals(0, result.getSkippedCount());

        verify(jdbcTemplate).queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, "10");
        verify(jdbcTemplate).queryForObject("SELECT set_config('app.user_role', ?, true)", String.class, "tenant_admin");
        verify(jdbcTemplate).update(contains("INSERT INTO order_loyalty_balances"),
                eq(10L), eq("PHONE:0901234567"), eq(250L));
    }

    @Test
    @DisplayName("Skips orders when not yet eligible (< 15 days) or cancelled/returned (no candidates returned by SQL)")
    void awardEligiblePointsForTenant_NoEligibleOrders_AwardsNothing() {
        when(jdbcTemplate.query(contains("FROM orders"), any(RowMapper.class), eq(10L)))
                .thenReturn(List.of());

        LoyaltyAwardResultDto result = service.awardEligiblePointsForTenant(10L, "TENANT_ADMIN");

        assertEquals(0, result.getOrdersEvaluated());
        assertEquals(0, result.getOrdersAwarded());
        assertEquals(0L, result.getTotalPointsAwarded());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO order_loyalty_ledger"), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Idempotent guard: if atomic UPDATE returns 0 (already awarded), never inserts ledger or adds balance")
    void awardEligiblePointsForTenant_AlreadyAwarded_IdempotentSkip() {
        EligibleOrderRow order = new EligibleOrderRow(101L, new BigDecimal("100000"), "0901234567", "Nguyen Van A");

        when(jdbcTemplate.query(contains("FROM orders"), any(RowMapper.class), eq(10L)))
                .thenReturn(List.of(order));
        when(jdbcTemplate.update(contains("UPDATE orders"), eq(100L), eq(10L), eq(101L)))
                .thenReturn(0);

        LoyaltyAwardResultDto result = service.awardEligiblePointsForTenant(10L, "TENANT_ADMIN");

        assertEquals(1, result.getOrdersEvaluated());
        assertEquals(0, result.getOrdersAwarded());
        assertEquals(0L, result.getTotalPointsAwarded());
        assertEquals(1, result.getSkippedCount());

        verify(jdbcTemplate, never()).update(contains("INSERT INTO order_loyalty_ledger"), any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO order_loyalty_balances"), any(), any(), any());
    }

    @Test
    @DisplayName("Controllers trigger current-tenant and all-tenant loyalty award flows")
    void controllers_TriggerAwardFlows() {
        when(jdbcTemplate.query(contains("FROM orders"), any(RowMapper.class), eq(10L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM tenants"), any(RowMapper.class)))
                .thenReturn(List.of(10L));

        LoyaltyAwardController awardController = new LoyaltyAwardController(service);
        try (var scope = TenantContext.openScope(10L, "TENANT_ADMIN", 1L)) {
            ResponseEntity<ApiResponse<LoyaltyAwardResultDto>> res = awardController.awardEligiblePoints();
            assertEquals(HttpStatus.OK, res.getStatusCode());
            assertNotNull(res.getBody());
        }

        InternalOrderJobsController jobsController = new InternalOrderJobsController(mock(OrderPollingScheduler.class), service);
        ResponseEntity<ApiResponse<LoyaltyAwardResultDto>> jobRes = jobsController.awardLoyaltyPoints();
        assertEquals(HttpStatus.OK, jobRes.getStatusCode());
        assertNotNull(jobRes.getBody());
    }
}
