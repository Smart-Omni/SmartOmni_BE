package com.smartomni.order.service;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.dto.LoyaltyAwardResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * WF-02 Part B: Automatically awards loyalty points for {@code COMPLETED} orders
 * once their 15-day return window ({@code eligible_for_points_at <= now()}) has passed.
 *
 * <p>Enforces PostgreSQL RLS context ({@code app.tenant_id}, {@code app.user_role}) and
 * dual idempotency via atomic {@code WHERE points_awarded_at IS NULL} plus unique
 * {@code idempotency_key = "ORDER_LOYALTY:" + orderId} in {@code order_loyalty_ledger}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyAwardService {

    private static final BigDecimal VND_PER_POINT = new BigDecimal("1000");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * Scans all active tenants and awards points for eligible completed orders.
     */
    public LoyaltyAwardResultDto awardEligiblePointsForAllTenants() {
        List<Long> tenantIds = transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', '', true)", String.class);
            jdbcTemplate.queryForObject("SELECT set_config('app.user_role', 'super_admin', true)", String.class);
            return jdbcTemplate.query(
                    "SELECT id FROM tenants WHERE status = 'active' ORDER BY id ASC",
                    (rs, i) -> rs.getLong("id"));
        });

        if (tenantIds == null || tenantIds.isEmpty()) {
            return LoyaltyAwardResultDto.builder().build();
        }

        int evaluated = 0;
        int awarded = 0;
        long totalPoints = 0;
        int skipped = 0;

        for (Long tenantId : tenantIds) {
            if (tenantId == null) {
                continue;
            }
            try (var scope = TenantContext.openScope(tenantId, "TENANT_ADMIN", null)) {
                LoyaltyAwardResultDto tenantResult = awardEligiblePointsForTenant(tenantId, "tenant_admin");
                evaluated += tenantResult.getOrdersEvaluated();
                awarded += tenantResult.getOrdersAwarded();
                totalPoints += tenantResult.getTotalPointsAwarded();
                skipped += tenantResult.getSkippedCount();
            } catch (Exception ex) {
                log.error("Failed to award loyalty points for tenantId={}: {}", tenantId, ex.getMessage(), ex);
            }
        }

        return LoyaltyAwardResultDto.builder()
                .ordersEvaluated(evaluated)
                .ordersAwarded(awarded)
                .totalPointsAwarded(totalPoints)
                .skippedCount(skipped)
                .build();
    }

    /**
     * Awards points for eligible completed orders of the currently authenticated tenant.
     */
    public LoyaltyAwardResultDto awardEligiblePointsForCurrentTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required", HttpStatus.FORBIDDEN, "TENANT_CONTEXT_REQUIRED");
        }
        String role = TenantContext.getCurrentRole() != null
                ? TenantContext.getCurrentRole().toLowerCase(Locale.ROOT)
                : "tenant_admin";
        return awardEligiblePointsForTenant(tenantId, role);
    }

    /**
     * Awards points for eligible completed orders of a single tenant inside an RLS-scoped transaction.
     */
    public LoyaltyAwardResultDto awardEligiblePointsForTenant(long tenantId, String role) {
        String effectiveRole = (role == null || role.isBlank()) ? "tenant_admin" : role.toLowerCase(Locale.ROOT);

        LoyaltyAwardResultDto result = transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, Long.toString(tenantId));
            jdbcTemplate.queryForObject("SELECT set_config('app.user_role', ?, true)", String.class, effectiveRole);

            List<EligibleOrderRow> candidates = jdbcTemplate.query(
                    "SELECT id, total_amount, customer_phone, customer_name "
                            + "FROM orders "
                            + "WHERE tenant_id = ? "
                            + "  AND status = 'completed' "
                            + "  AND eligible_for_points_at IS NOT NULL "
                            + "  AND eligible_for_points_at <= now() "
                            + "  AND points_awarded_at IS NULL "
                            + "  AND NOT EXISTS ("
                            + "    SELECT 1 FROM returns_refunds rr "
                            + "    WHERE rr.tenant_id = orders.tenant_id "
                            + "      AND rr.order_id = orders.id "
                            + "      AND rr.status IN ('approved', 'completed')"
                            + "  ) "
                            + "ORDER BY id ASC",
                    (rs, i) -> new EligibleOrderRow(
                            rs.getLong("id"),
                            rs.getBigDecimal("total_amount"),
                            rs.getString("customer_phone"),
                            rs.getString("customer_name")),
                    tenantId);

            int evaluated = candidates.size();
            int awarded = 0;
            long totalPoints = 0;
            int skipped = 0;

            for (EligibleOrderRow order : candidates) {
                long points = calculatePoints(order.totalAmount());
                if (points <= 0) {
                    skipped++;
                    continue;
                }

                int orderUpdated = jdbcTemplate.update(
                        "UPDATE orders "
                                + "SET points_awarded_at = now(), points_awarded = ?, updated_at = now() "
                                + "WHERE tenant_id = ? AND id = ? "
                                + "  AND points_awarded_at IS NULL "
                                + "  AND status = 'completed' "
                                + "  AND eligible_for_points_at IS NOT NULL "
                                + "  AND eligible_for_points_at <= now()",
                        points, tenantId, order.id());

                if (orderUpdated != 1) {
                    skipped++;
                    continue;
                }

                String customerKey = resolveCustomerKey(order);
                String idempotencyKey = "ORDER_LOYALTY:" + order.id();

                int ledgerInserted = jdbcTemplate.update(
                        "INSERT INTO order_loyalty_ledger "
                                + "(tenant_id, order_id, customer_key, points_awarded, eligible_amount_vnd, idempotency_key, awarded_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, now()) "
                                + "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING",
                        tenantId, order.id(), customerKey, points, order.totalAmount(), idempotencyKey);

                if (ledgerInserted == 1) {
                    jdbcTemplate.update(
                            "INSERT INTO order_loyalty_balances (tenant_id, customer_key, total_points, updated_at) "
                                    + "VALUES (?, ?, ?, now()) "
                                    + "ON CONFLICT (tenant_id, customer_key) DO UPDATE "
                                    + "SET total_points = order_loyalty_balances.total_points + EXCLUDED.total_points, "
                                    + "    updated_at = now()",
                            tenantId, customerKey, points);
                    awarded++;
                    totalPoints += points;
                } else {
                    skipped++;
                }
            }

            return LoyaltyAwardResultDto.builder()
                    .ordersEvaluated(evaluated)
                    .ordersAwarded(awarded)
                    .totalPointsAwarded(totalPoints)
                    .skippedCount(skipped)
                    .build();
        });

        if (result != null && result.getOrdersAwarded() > 0) {
            log.info("WF-02 Loyalty points awarded: tenantId={}, evaluated={}, awarded={}, totalPoints={}, skipped={}",
                    tenantId, result.getOrdersEvaluated(), result.getOrdersAwarded(),
                    result.getTotalPointsAwarded(), result.getSkippedCount());
        }
        return result != null ? result : LoyaltyAwardResultDto.builder().build();
    }

    static long calculatePoints(BigDecimal totalAmount) {
        if (totalAmount == null || totalAmount.compareTo(VND_PER_POINT) < 0) {
            return 0L;
        }
        return totalAmount.divideToIntegralValue(VND_PER_POINT).longValue();
    }

    static String resolveCustomerKey(EligibleOrderRow order) {
        if (order.customerPhone() != null && !order.customerPhone().isBlank()) {
            return "PHONE:" + order.customerPhone().trim();
        }
        if (order.customerName() != null && !order.customerName().isBlank()) {
            return "NAME:" + order.customerName().trim().toLowerCase(Locale.ROOT);
        }
        return "ORDER:" + order.id();
    }

    record EligibleOrderRow(long id, BigDecimal totalAmount, String customerPhone, String customerName) {
    }
}
