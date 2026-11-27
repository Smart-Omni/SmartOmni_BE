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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles customer manual order claims, cumulative point balance, and tier calculation (WF-02).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyCustomerService {

    /** Vietnam calendar date shown to the shopper. */
    private static final java.time.format.DateTimeFormatter ELIGIBLE_DATE =
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public LoyaltyClaimDto claimOrderPoints(Long customerId, Long tenantId, ClaimPointsRequest request) {
        requireCustomerTenantContext(customerId, tenantId);
        boolean byPlatformOrderId = request != null && request.byPlatformOrderId();
        if (request == null || (request.getOrderId() == null && !byPlatformOrderId)) {
            throw new BusinessException("platformOrderId (or orderId) is required", HttpStatus.BAD_REQUEST, "INVALID_CLAIM_REQUEST");
        }
        if (!byPlatformOrderId
                && (request.getTotalAmount() == null || request.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("totalAmount is required and must be greater than 0",
                    HttpStatus.BAD_REQUEST, "INVALID_TOTAL_AMOUNT");
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

        // The shopper pastes the marketplace order id; who bought the order is not checked, only that it is unclaimed.
        Long orderId = byPlatformOrderId
                ? resolvePlatformOrder(tenantId, request.getPlatformOrderId(), request.getPlatform())
                : request.getOrderId();

        // Lock and verify order eligibility
        List<OrderEligibilityRow> orders = jdbcTemplate.query(
                "SELECT id, status::text AS status, total_amount, eligible_for_points_at "
                        + "FROM orders WHERE tenant_id = ? AND id = ? FOR SHARE",
                (rs, rowNum) -> new OrderEligibilityRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount"),
                        toInstant(rs.getTimestamp("eligible_for_points_at"))),
                tenantId, orderId);

        if (orders.isEmpty()) {
            throw new BusinessException("Order not found in tenant", HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        }

        OrderEligibilityRow order = orders.get(0);
        if (!"completed".equalsIgnoreCase(order.status())) {
            throw new BusinessException("Only COMPLETED orders can be claimed for loyalty points",
                    HttpStatus.BAD_REQUEST, "ORDER_NOT_COMPLETED");
        }
        if (order.eligibleForPointsAt() == null || order.eligibleForPointsAt().isAfter(Instant.now())) {
            String when = order.eligibleForPointsAt() == null ? ""
                    : " (eligible from " + ELIGIBLE_DATE.format(order.eligibleForPointsAt()) + ")";
            throw new BusinessException("Order has not reached the 15-day eligibility date yet" + when,
                    HttpStatus.BAD_REQUEST, "ORDER_NOT_ELIGIBLE_YET");
        }
        if (order.totalAmount() == null || order.totalAmount().compareTo(BigDecimal.valueOf(1000)) < 0) {
            throw new BusinessException("Order total amount must be at least 1,000 VND",
                    HttpStatus.BAD_REQUEST, "ORDER_AMOUNT_TOO_LOW");
        }
        if (!byPlatformOrderId && order.totalAmount().compareTo(request.getTotalAmount()) != 0) {
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
        LoyaltyProfileDto profile = LoyaltyTierCalculator.buildProfile(customerId, tenantId, totalPoints);
        List<LoyaltyProfileDto.PlatformProgress> platforms = loadPlatformProgress(customerId, tenantId, totalPoints);
        profile.setPlatforms(platforms);
        applyConfiguredTiers(profile, platforms);
        return profile;
    }

    /**
     * Milestones = ACTIVE, running reward campaigns of each CONNECTED Shopee/TikTok shop (one campaign per tier),
     * with the customer's latest reward request for each.
     */
    private List<LoyaltyProfileDto.PlatformProgress> loadPlatformProgress(Long customerId, Long tenantId, long points) {
        Map<Long, LoyaltyProfileDto.Reward> rewards = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT DISTINCT ON (campaign_id) campaign_id, id, status, code, failure_reason "
                        + "FROM loyalty_reward_requests WHERE tenant_id = ? AND customer_id = ? "
                        + "ORDER BY campaign_id, requested_at DESC, id DESC",
                (java.sql.ResultSet rs) -> {
                    rewards.put(rs.getLong("campaign_id"), LoyaltyProfileDto.Reward.builder()
                            .requestId(rs.getLong("id"))
                            .status(rs.getString("status"))
                            .code(rs.getString("code"))
                            .failureReason(rs.getString("failure_reason"))
                            .build());
                },
                tenantId, customerId);

        Map<Long, LoyaltyProfileDto.PlatformProgress> byConnection = new java.util.LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT c.id, c.connection_id, mc.platform::text AS platform, mc.shop_name, c.name, c.required_points, "
                        + "c.discount_percent, c.quantity - c.reserved_count AS available "
                        + "FROM loyalty_reward_campaigns c "
                        + "JOIN marketplace_connections mc ON mc.tenant_id = c.tenant_id AND mc.id = c.connection_id "
                        + "WHERE c.tenant_id = ? AND c.status = 'active' AND mc.status = 'connected' "
                        + "AND mc.platform::text IN ('shopee', 'tiktok_shop') "
                        + "AND c.starts_at <= now() AND c.ends_at > now() "
                        + "ORDER BY mc.platform, c.connection_id, c.required_points, c.id",
                (java.sql.ResultSet rs) -> {
                    long campaignId = rs.getLong("id");
                    long required = rs.getLong("required_points");
                    LoyaltyProfileDto.PlatformProgress progress = byConnection.computeIfAbsent(rs.getLong("connection_id"),
                            id -> LoyaltyProfileDto.PlatformProgress.builder()
                                    .platform(stringOf(rs, "platform"))
                                    .connectionId(id)
                                    .shopName(stringOf(rs, "shop_name"))
                                    .milestones(new ArrayList<>())
                                    .build());
                    progress.getMilestones().add(LoyaltyProfileDto.Milestone.builder()
                            .campaignId(campaignId)
                            .name(rs.getString("name"))
                            .requiredPoints(required)
                            .discountPercent(rs.getBigDecimal("discount_percent"))
                            .reached(points >= required)
                            .availableQuantity(Math.max(0, rs.getInt("available")))
                            .reward(rewards.get(campaignId))
                            .build());
                },
                tenantId);

        for (LoyaltyProfileDto.PlatformProgress progress : byConnection.values()) {
            for (LoyaltyProfileDto.Milestone m : progress.getMilestones()) {
                if (m.isReached()) {
                    progress.setCurrentTier(m.getName());
                } else if (progress.getNextTier() == null) {
                    progress.setNextTier(m.getName());
                    progress.setNextTierThreshold(m.getRequiredPoints());
                    progress.setPointsToNextTier(m.getRequiredPoints() - points);
                }
            }
        }
        return new ArrayList<>(byConnection.values());
    }

    /** When the shop configured tiers, the summary tier follows them instead of the built-in defaults. */
    private static void applyConfiguredTiers(LoyaltyProfileDto profile, List<LoyaltyProfileDto.PlatformProgress> platforms) {
        List<LoyaltyProfileDto.Milestone> all = platforms.stream().flatMap(p -> p.getMilestones().stream()).toList();
        if (all.isEmpty()) {
            return;
        }
        LoyaltyProfileDto.Milestone current = all.stream().filter(LoyaltyProfileDto.Milestone::isReached)
                .max(java.util.Comparator.comparingLong(LoyaltyProfileDto.Milestone::getRequiredPoints)).orElse(null);
        LoyaltyProfileDto.Milestone next = all.stream().filter(m -> !m.isReached())
                .min(java.util.Comparator.comparingLong(LoyaltyProfileDto.Milestone::getRequiredPoints)).orElse(null);
        profile.setTier(current == null ? "Standard" : current.getName());
        profile.setNextTier(next == null ? null : next.getName());
        profile.setNextTierThreshold(next == null ? null : next.getRequiredPoints());
        profile.setPointsToNextTier(next == null ? 0L : next.getRequiredPoints() - profile.getTotalPoints());
    }

    private static String stringOf(java.sql.ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
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

    /**
     * Internal id of the tenant's order with this marketplace id. 404 when unknown, 409 PLATFORM_REQUIRED when the id
     * exists on more than one marketplace (or shop) and the request did not say which.
     */
    private Long resolvePlatformOrder(Long tenantId, String platformOrderId, String platform) {
        String normalizedPlatform = normalizePlatform(platform);
        List<Long> ids = normalizedPlatform == null
                ? jdbcTemplate.query("SELECT id FROM orders WHERE tenant_id = ? AND platform_order_id = ? ORDER BY id",
                        (rs, i) -> rs.getLong("id"), tenantId, platformOrderId.trim())
                : jdbcTemplate.query("SELECT id FROM orders WHERE tenant_id = ? AND platform_order_id = ? "
                                + "AND platform::text = ? ORDER BY id",
                        (rs, i) -> rs.getLong("id"), tenantId, platformOrderId.trim(), normalizedPlatform);
        if (ids.isEmpty()) {
            throw new BusinessException("Order not found in tenant", HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        }
        if (ids.size() > 1) {
            throw new BusinessException("This order id exists on more than one marketplace; choose the marketplace",
                    HttpStatus.CONFLICT, "PLATFORM_REQUIRED");
        }
        return ids.get(0);
    }

    static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        String p = platform.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (p) {
            case "shopee" -> "shopee";
            case "tiktok", "tiktok_shop" -> "tiktok_shop";
            default -> throw new BusinessException("platform must be shopee or tiktok_shop",
                    HttpStatus.BAD_REQUEST, "INVALID_PLATFORM");
        };
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
