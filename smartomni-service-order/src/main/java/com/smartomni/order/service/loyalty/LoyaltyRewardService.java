package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.CreateRewardRequest;
import com.smartomni.order.dto.loyalty.LoyaltyRewardRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Handles customer reward voucher redemption requests (without deducting points)
 * and dispatches asynchronous marketplace confirmation (WF-02).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyRewardService {

    private final JdbcTemplate jdbcTemplate;
    private final LoyaltyVoucherWorker voucherWorker;

    @Transactional
    public LoyaltyRewardRequestDto requestReward(Long customerId, Long tenantId, CreateRewardRequest request) {
        if (tenantId == null || customerId == null) {
            throw new BusinessException("Customer JWT with userId and tenantId is required",
                    HttpStatus.FORBIDDEN, "TENANT_OR_USER_MISSING");
        }
        if (request == null || request.getCampaignId() == null
                || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new BusinessException("campaignId and idempotencyKey are required",
                    HttpStatus.BAD_REQUEST, "INVALID_REWARD_REQUEST");
        }

        UUID requestKey = resolveRequestKey(request.getIdempotencyKey().trim());

        // 1. Idempotency check by request_key
        List<LoyaltyRewardRequestDto> existingByKey = jdbcTemplate.query(
                BASE_REQUEST_SELECT + " WHERE r.tenant_id = ? AND r.request_key = ?",
                this::mapRequest,
                tenantId, requestKey);
        if (!existingByKey.isEmpty()) {
            LoyaltyRewardRequestDto existing = existingByKey.get(0);
            if (!existing.getCustomerId().equals(customerId) || !existing.getCampaignId().equals(request.getCampaignId())) {
                throw new BusinessException("Idempotency key already used for a different request",
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");
            }
            return existing;
        }

        // 2. Verify active CUSTOMER role in tenant
        List<String> roles = jdbcTemplate.query(
                "SELECT role::text FROM users WHERE tenant_id = ? AND id = ? AND status = 'active'",
                (rs, rowNum) -> rs.getString("role"),
                tenantId, customerId);
        if (roles.isEmpty() || !"customer".equalsIgnoreCase(roles.get(0))) {
            throw new BusinessException("Only active CUSTOMER users can request loyalty vouchers",
                    HttpStatus.FORBIDDEN, "FORBIDDEN_CUSTOMER_ROLE");
        }

        // 3. Load campaign + connection state
        List<CampaignRow> campaigns = jdbcTemplate.query(
                "SELECT c.id, c.required_points, c.quantity, c.reserved_count, c.status, "
                        + "c.starts_at, c.ends_at, mc.status::text AS conn_status "
                        + "FROM loyalty_reward_campaigns c "
                        + "JOIN marketplace_connections mc ON mc.tenant_id = c.tenant_id AND mc.id = c.connection_id "
                        + "WHERE c.tenant_id = ? AND c.id = ?",
                (rs, rowNum) -> new CampaignRow(
                        rs.getLong("id"),
                        rs.getLong("required_points"),
                        rs.getInt("quantity"),
                        rs.getInt("reserved_count"),
                        rs.getString("status"),
                        toInstant(rs.getTimestamp("starts_at")),
                        toInstant(rs.getTimestamp("ends_at")),
                        rs.getString("conn_status")),
                tenantId, request.getCampaignId());
        if (campaigns.isEmpty()) {
            throw new BusinessException("Campaign not found in tenant",
                    HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND");
        }
        CampaignRow campaign = campaigns.get(0);
        Instant now = Instant.now();
        if (!"connected".equalsIgnoreCase(campaign.connStatus())) {
            throw new BusinessException("Shop connection is no longer CONNECTED",
                    HttpStatus.BAD_REQUEST, "CONNECTION_NOT_CONNECTED");
        }
        if (!"active".equalsIgnoreCase(campaign.status())
                || campaign.startsAt().isAfter(now)
                || !campaign.endsAt().isAfter(now)) {
            throw new BusinessException("Campaign is not currently active",
                    HttpStatus.BAD_REQUEST, "CAMPAIGN_INACTIVE");
        }
        if (campaign.reservedCount() >= campaign.quantity()) {
            throw new BusinessException("Campaign voucher quota exhausted",
                    HttpStatus.CONFLICT, "CAMPAIGN_QUOTA_EXHAUSTED");
        }

        // 4. One active/issued reward per customer per campaign
        Integer activeForCampaign = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loyalty_reward_requests "
                        + "WHERE tenant_id = ? AND campaign_id = ? AND customer_id = ? AND status IN ('pending', 'issued')",
                Integer.class, tenantId, campaign.id(), customerId);
        if (activeForCampaign != null && activeForCampaign > 0) {
            throw new BusinessException("Customer has already claimed or requested this campaign",
                    HttpStatus.CONFLICT, "REWARD_ALREADY_REQUESTED");
        }

        // 5. Verify cumulative points >= required_points (never deduct points)
        List<Long> balances = jdbcTemplate.query(
                "SELECT total_points FROM loyalty_point_accounts WHERE tenant_id = ? AND customer_id = ?",
                (rs, rowNum) -> rs.getLong("total_points"),
                tenantId, customerId);
        long currentPoints = balances.isEmpty() ? 0L : balances.get(0);
        if (currentPoints < campaign.requiredPoints()) {
            throw new BusinessException(
                    String.format("Insufficient loyalty points: current=%d, required=%d",
                            currentPoints, campaign.requiredPoints()),
                    HttpStatus.BAD_REQUEST,
                    "INSUFFICIENT_LOYALTY_POINTS");
        }

        // 6. Insert pending reward request (DB trigger reserves 1 slot atomically)
        Long requestId;
        try {
            requestId = jdbcTemplate.queryForObject(
                    "INSERT INTO loyalty_reward_requests "
                            + "(tenant_id, campaign_id, customer_id, request_key, status) "
                            + "VALUES (?, ?, ?, ?, 'pending') RETURNING id",
                    Long.class, tenantId, campaign.id(), customerId, requestKey);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent reward request conflict for tenantId={}, campaignId={}, customerId={}: {}",
                    tenantId, campaign.id(), customerId, ex.getMessage());
            throw new BusinessException("Reward request could not be reserved (already requested or quota exhausted)",
                    HttpStatus.CONFLICT, "REWARD_RESERVATION_CONFLICT");
        }

        LoyaltyRewardRequestDto created = jdbcTemplate.queryForObject(
                BASE_REQUEST_SELECT + " WHERE r.tenant_id = ? AND r.id = ?",
                this::mapRequest,
                tenantId, requestId);

        // 7. Dispatch async worker AFTER transaction commit
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    voucherWorker.processRequestAsync(tenantId, requestId);
                }
            });
        } else {
            voucherWorker.processRequestAsync(tenantId, requestId);
        }

        return created;
    }

    @Transactional(readOnly = true)
    public List<LoyaltyRewardRequestDto> listMyVouchers(Long customerId, Long tenantId) {
        if (tenantId == null || customerId == null) {
            throw new BusinessException("Customer JWT with userId and tenantId is required",
                    HttpStatus.FORBIDDEN, "TENANT_OR_USER_MISSING");
        }
        return jdbcTemplate.query(
                BASE_REQUEST_SELECT
                        + " WHERE r.tenant_id = ? AND r.customer_id = ? "
                        + "ORDER BY r.requested_at DESC, r.id DESC",
                this::mapRequest,
                tenantId, customerId);
    }

    private LoyaltyRewardRequestDto mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return LoyaltyRewardRequestDto.builder()
                .id(rs.getLong("id"))
                .tenantId(rs.getLong("tenant_id"))
                .campaignId(rs.getLong("campaign_id"))
                .campaignName(rs.getString("campaign_name"))
                .discountPercent(rs.getBigDecimal("discount_percent"))
                .customerId(rs.getLong("customer_id"))
                .requestKey(rs.getString("request_key"))
                .status(rs.getString("status"))
                .code(rs.getString("code"))
                .externalVoucherId(rs.getString("external_voucher_id"))
                .failureReason(rs.getString("failure_reason"))
                .requestedAt(toInstant(rs.getTimestamp("requested_at")))
                .issuedAt(toInstant(rs.getTimestamp("issued_at")))
                .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                .build();
    }

    private static UUID resolveRequestKey(String rawKey) {
        try {
            return UUID.fromString(rawKey);
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(rawKey.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static final String BASE_REQUEST_SELECT =
            "SELECT r.id, r.tenant_id, r.campaign_id, c.name AS campaign_name, c.discount_percent, "
                    + "r.customer_id, r.request_key::text AS request_key, r.status, r.code, "
                    + "r.external_voucher_id, r.failure_reason, r.requested_at, r.issued_at, r.updated_at "
                    + "FROM loyalty_reward_requests r "
                    + "JOIN loyalty_reward_campaigns c ON c.tenant_id = r.tenant_id AND c.id = r.campaign_id";

    private record CampaignRow(
            Long id,
            long requiredPoints,
            int quantity,
            int reservedCount,
            String status,
            Instant startsAt,
            Instant endsAt,
            String connStatus) {
    }
}
