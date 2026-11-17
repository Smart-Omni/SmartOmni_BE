package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.ClaimPointsRequest;
import com.smartomni.order.dto.loyalty.LoyaltyClaimDto;
import com.smartomni.order.dto.loyalty.LoyaltyProfileDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Handles customer manual order claims, cumulative point balance, and tier calculation (WF-02).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyCustomerService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public LoyaltyClaimDto claimOrderPoints(Long customerId, Long tenantId, ClaimPointsRequest request) {
        requireCustomerTenantContext(customerId, tenantId);
        if (request == null || request.getOrderId() == null) {
            throw new BusinessException("orderId is required", HttpStatus.BAD_REQUEST, "INVALID_CLAIM_REQUEST");
        }

        // Verify customer belongs to the same tenant and is active with role 'customer'
        List<String> roles = jdbcTemplate.query(
                "SELECT role::text FROM users WHERE tenant_id = ? AND id = ? AND status = 'active'",
                (rs, rowNum) -> rs.getString("role"),
                tenantId, customerId);
        if (roles.isEmpty() || !"customer".equalsIgnoreCase(roles.get(0))) {
            throw new BusinessException("Only active CUSTOMER users of this tenant can claim loyalty points",
                    HttpStatus.FORBIDDEN, "FORBIDDEN_CUSTOMER_ROLE");
        }

        // Lock and verify order eligibility
        List<OrderEligibilityRow> orders = jdbcTemplate.query(
                "SELECT id, status::text AS status, total_amount, eligible_for_points_at "
                        + "FROM orders WHERE tenant_id = ? AND id = ? FOR SHARE",
                (rs, rowNum) -> new OrderEligibilityRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount"),
                        toInstant(rs.getTimestamp("eligible_for_points_at"))),
                tenantId, request.getOrderId());

        if (orders.isEmpty()) {
            throw new BusinessException("Order not found in tenant", HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        }

        OrderEligibilityRow order = orders.get(0);
        if (!"completed".equalsIgnoreCase(order.status())) {
            throw new BusinessException("Only COMPLETED orders can be claimed for loyalty points",
                    HttpStatus.BAD_REQUEST, "ORDER_NOT_COMPLETED");
        }
        if (order.eligibleForPointsAt() == null || order.eligibleForPointsAt().isAfter(Instant.now())) {
            throw new BusinessException("Order has not reached the 15-day eligibility date yet",
                    HttpStatus.BAD_REQUEST, "ORDER_NOT_ELIGIBLE_YET");
        }
        if (order.totalAmount() == null || order.totalAmount().compareTo(BigDecimal.valueOf(1000)) < 0) {
            throw new BusinessException("Order total amount must be at least 1,000 VND",
                    HttpStatus.BAD_REQUEST, "ORDER_AMOUNT_TOO_LOW");
        }
        if (request.getTotalAmount() != null && order.totalAmount().compareTo(request.getTotalAmount()) != 0) {
            throw new BusinessException("Order total amount does not match",
                    HttpStatus.BAD_REQUEST, "ORDER_AMOUNT_MISMATCH");
        }

        Integer approvedReturns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM returns_refunds "
                        + "WHERE tenant_id = ? AND order_id = ? AND status IN ('approved', 'completed')",
                Integer.class, tenantId, order.id());
        if (approvedReturns != null && approvedReturns > 0) {
            throw new BusinessException("Order with an approved or completed return/refund cannot be claimed",
                    HttpStatus.BAD_REQUEST, "ORDER_HAS_APPROVED_RETURN");
        }

        Integer existingClaims = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loyalty_claims WHERE tenant_id = ? AND order_id = ?",
                Integer.class, tenantId, order.id());
        if (existingClaims != null && existingClaims > 0) {
            throw new BusinessException("This order has already been claimed",
                    HttpStatus.CONFLICT, "ORDER_ALREADY_CLAIMED");
        }

        long expectedPoints = order.totalAmount()
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.FLOOR)
                .longValue();

        try {
            return jdbcTemplate.queryForObject(
                    "INSERT INTO loyalty_claims (tenant_id, order_id, customer_id, points_awarded) "
                            + "VALUES (?, ?, ?, ?) "
                            + "RETURNING id, order_id, customer_id, eligible_amount_vnd, points_awarded, claimed_at",
                    (rs, rowNum) -> LoyaltyClaimDto.builder()
                            .id(rs.getLong("id"))
                            .orderId(rs.getLong("order_id"))
                            .customerId(rs.getLong("customer_id"))
                            .eligibleAmountVnd(rs.getBigDecimal("eligible_amount_vnd"))
                            .pointsAwarded(rs.getLong("points_awarded"))
                            .claimedAt(toInstant(rs.getTimestamp("claimed_at")))
                            .build(),
                    tenantId, order.id(), customerId, expectedPoints);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Duplicate or invalid claim race for tenantId={}, orderId={}: {}",
                    tenantId, order.id(), ex.getMessage());
            throw new BusinessException("This order has already been claimed",
                    HttpStatus.CONFLICT, "ORDER_ALREADY_CLAIMED");
        }
    }

    @Transactional(readOnly = true)
    public LoyaltyProfileDto getMyProfile(Long customerId, Long tenantId) {
        requireCustomerTenantContext(customerId, tenantId);
        List<Long> balances = jdbcTemplate.query(
                "SELECT total_points FROM loyalty_point_accounts WHERE tenant_id = ? AND customer_id = ?",
                (rs, rowNum) -> rs.getLong("total_points"),
                tenantId, customerId);
        long totalPoints = balances.isEmpty() ? 0L : balances.get(0);
        return LoyaltyTierCalculator.buildProfile(customerId, tenantId, totalPoints);
    }

    @Transactional(readOnly = true)
    public List<LoyaltyClaimDto> getMyClaims(Long customerId, Long tenantId) {
        requireCustomerTenantContext(customerId, tenantId);
        return jdbcTemplate.query(
                "SELECT id, order_id, customer_id, eligible_amount_vnd, points_awarded, claimed_at "
                        + "FROM loyalty_claims WHERE tenant_id = ? AND customer_id = ? "
                        + "ORDER BY claimed_at DESC, id DESC",
                (rs, rowNum) -> LoyaltyClaimDto.builder()
                        .id(rs.getLong("id"))
                        .orderId(rs.getLong("order_id"))
                        .customerId(rs.getLong("customer_id"))
                        .eligibleAmountVnd(rs.getBigDecimal("eligible_amount_vnd"))
                        .pointsAwarded(rs.getLong("points_awarded"))
                        .claimedAt(toInstant(rs.getTimestamp("claimed_at")))
                        .build(),
                tenantId, customerId);
    }

    private static void requireCustomerTenantContext(Long customerId, Long tenantId) {
        if (tenantId == null || customerId == null) {
            throw new BusinessException("Customer JWT with valid userId and tenantId is required",
                    HttpStatus.FORBIDDEN, "TENANT_OR_USER_MISSING");
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private record OrderEligibilityRow(Long id, String status, BigDecimal totalAmount, Instant eligibleForPointsAt) {
    }
}
