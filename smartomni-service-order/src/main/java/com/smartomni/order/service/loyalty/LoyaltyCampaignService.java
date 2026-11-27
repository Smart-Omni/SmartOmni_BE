package com.smartomni.order.service.loyalty;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.dto.loyalty.CreateCampaignRequest;
import com.smartomni.order.dto.loyalty.LoyaltyCampaignDto;
import com.smartomni.order.dto.loyalty.LoyaltyRewardRequestDto;
import com.smartomni.order.dto.loyalty.UpdateCampaignRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manages loyalty reward campaigns (Admin CRUD & Customer active campaign listing) (WF-02).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyCampaignService {

    private static final Set<String> VALID_MODES = Set.of("api", "import");
    private static final Set<String> VALID_STATUSES = Set.of("draft", "active", "paused", "closed");

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public LoyaltyCampaignDto createCampaign(Long staffUserId, Long tenantId, CreateCampaignRequest request) {
        if (tenantId == null || staffUserId == null) {
            throw new BusinessException("Authenticated tenant admin/staff context is required",
                    HttpStatus.FORBIDDEN, "TENANT_OR_USER_MISSING");
        }
        validateCampaignDates(request.getStartsAt(), request.getEndsAt());

        String mode = normalizeMode(request.getProvisioningMode());
        String status = normalizeStatus(request.getStatus(), "active");

        List<ConnectionRow> connections = jdbcTemplate.query(
                "SELECT id, platform::text AS platform, status::text AS status, shop_name "
                        + "FROM marketplace_connections WHERE tenant_id = ? AND id = ?",
                (rs, rowNum) -> new ConnectionRow(
                        rs.getLong("id"),
                        rs.getString("platform"),
                        rs.getString("status"),
                        rs.getString("shop_name")),
                tenantId, request.getConnectionId());
        if (connections.isEmpty()) {
            throw new BusinessException("Marketplace connection not found in tenant",
                    HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND");
        }
        ConnectionRow conn = connections.get(0);
        if (!"connected".equalsIgnoreCase(conn.status())) {
            throw new BusinessException("Marketplace connection must be CONNECTED (current=" + conn.status() + ")",
                    HttpStatus.BAD_REQUEST, "CONNECTION_NOT_CONNECTED");
        }
        if (!"shopee".equalsIgnoreCase(conn.platform()) && !"tiktok_shop".equalsIgnoreCase(conn.platform())) {
            throw new BusinessException("Campaign only supports Shopee or TikTok Shop connections",
                    HttpStatus.BAD_REQUEST, "UNSUPPORTED_PLATFORM");
        }

        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO loyalty_reward_campaigns "
                        + "(tenant_id, connection_id, created_by, name, discount_percent, required_points, "
                        + "quantity, provisioning_mode, external_coupon_id, tiktok_batch_title, status, starts_at, ends_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                tenantId,
                conn.id(),
                staffUserId,
                request.getName().trim(),
                request.getDiscountPercent(),
                request.getRequiredPoints(),
                request.getQuantity(),
                mode,
                trimToNull(request.getExternalCouponId()),
                trimToNull(request.getTiktokBatchTitle()),
                status,
                Timestamp.from(request.getStartsAt()),
                Timestamp.from(request.getEndsAt()));

        return getCampaignById(tenantId, id);
    }

    @Transactional(readOnly = true)
    public List<LoyaltyCampaignDto> listAdminCampaigns(Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required", HttpStatus.FORBIDDEN, "TENANT_MISSING");
        }
        return jdbcTemplate.query(
                BASE_CAMPAIGN_SELECT + " WHERE c.tenant_id = ? ORDER BY c.created_at DESC, c.id DESC",
                this::mapCampaign,
                tenantId);
    }

    @Transactional(readOnly = true)
    public List<LoyaltyCampaignDto> listActiveCampaignsForCustomer(Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("Customer JWT with tenantId is required",
                    HttpStatus.FORBIDDEN, "TENANT_MISSING");
        }
        return jdbcTemplate.query(
                BASE_CAMPAIGN_SELECT
                        + " WHERE c.tenant_id = ? AND c.status = 'active' "
                        + "AND mc.status = 'connected' "
                        + "AND c.starts_at <= now() AND c.ends_at > now() "
                        + "ORDER BY c.required_points ASC, c.id DESC",
                this::mapCampaign,
                tenantId);
    }

    @Transactional
    public LoyaltyCampaignDto updateCampaign(Long tenantId, Long campaignId, UpdateCampaignRequest request) {
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required", HttpStatus.FORBIDDEN, "TENANT_MISSING");
        }
        LoyaltyCampaignDto existing = getCampaignById(tenantId, campaignId);

        String name = request.getName() != null && !request.getName().isBlank()
                ? request.getName().trim() : existing.getName();
        BigDecimal discountPercent = request.getDiscountPercent() != null
                ? request.getDiscountPercent() : existing.getDiscountPercent();
        if (discountPercent.compareTo(BigDecimal.ZERO) <= 0
                || discountPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("discountPercent must be > 0 and <= 100",
                    HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_PERCENT");
        }
        int quantity = request.getQuantity() != null ? request.getQuantity() : existing.getQuantity();
        if (quantity < existing.getReservedCount() || quantity <= 0) {
            throw new BusinessException("quantity must be > 0 and >= reservedCount (" + existing.getReservedCount() + ")",
                    HttpStatus.BAD_REQUEST, "INVALID_CAMPAIGN_QUANTITY");
        }
        long requiredPoints = request.getRequiredPoints() != null
                ? request.getRequiredPoints() : existing.getRequiredPoints();
        if (requiredPoints <= 0) {
            throw new BusinessException("requiredPoints must be > 0",
                    HttpStatus.BAD_REQUEST, "INVALID_REQUIRED_POINTS");
        }
        String mode = request.getProvisioningMode() != null
                ? normalizeMode(request.getProvisioningMode()) : existing.getProvisioningMode();
        String externalCouponId = request.getExternalCouponId() != null
                ? trimToNull(request.getExternalCouponId()) : existing.getExternalCouponId();
        String tiktokBatchTitle = request.getTiktokBatchTitle() != null
                ? trimToNull(request.getTiktokBatchTitle()) : existing.getTiktokBatchTitle();
        String status = request.getStatus() != null
                ? normalizeStatus(request.getStatus(), existing.getStatus()) : existing.getStatus();
        Instant startsAt = request.getStartsAt() != null ? request.getStartsAt() : existing.getStartsAt();
        Instant endsAt = request.getEndsAt() != null ? request.getEndsAt() : existing.getEndsAt();
        validateCampaignDates(startsAt, endsAt);

        jdbcTemplate.update(
                "UPDATE loyalty_reward_campaigns SET name = ?, discount_percent = ?, required_points = ?, "
                        + "quantity = ?, provisioning_mode = ?, external_coupon_id = ?, tiktok_batch_title = ?, status = ?, "
                        + "starts_at = ?, ends_at = ?, updated_at = now() "
                        + "WHERE tenant_id = ? AND id = ?",
                name, discountPercent, requiredPoints, quantity, mode, externalCouponId, tiktokBatchTitle, status,
                Timestamp.from(startsAt), Timestamp.from(endsAt), tenantId, campaignId);

        return getCampaignById(tenantId, campaignId);
    }

    @Transactional(readOnly = true)
    public List<LoyaltyRewardRequestDto> listAdminRewardRequests(Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required", HttpStatus.FORBIDDEN, "TENANT_MISSING");
        }
        return jdbcTemplate.query(
                "SELECT r.id, r.tenant_id, r.campaign_id, c.name AS campaign_name, c.discount_percent, "
                        + "r.customer_id, r.request_key::text AS request_key, r.status, r.code, "
                        + "r.external_voucher_id, r.failure_reason, r.requested_at, r.issued_at, r.updated_at "
                        + "FROM loyalty_reward_requests r "
                        + "JOIN loyalty_reward_campaigns c ON c.tenant_id = r.tenant_id AND c.id = r.campaign_id "
                        + "WHERE r.tenant_id = ? "
                        + "ORDER BY r.requested_at DESC, r.id DESC",
                (rs, rowNum) -> LoyaltyRewardRequestDto.builder()
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
                        .build(),
                tenantId);
    }

    private LoyaltyCampaignDto getCampaignById(Long tenantId, Long campaignId) {
        List<LoyaltyCampaignDto> rows = jdbcTemplate.query(
                BASE_CAMPAIGN_SELECT + " WHERE c.tenant_id = ? AND c.id = ?",
                this::mapCampaign,
                tenantId, campaignId);
        if (rows.isEmpty()) {
            throw new BusinessException("Campaign not found", HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND");
        }
        return rows.get(0);
    }

    private LoyaltyCampaignDto mapCampaign(ResultSet rs, int rowNum) throws SQLException {
        int qty = rs.getInt("quantity");
        int reserved = rs.getInt("reserved_count");
        return LoyaltyCampaignDto.builder()
                .id(rs.getLong("id"))
                .tenantId(rs.getLong("tenant_id"))
                .connectionId(rs.getLong("connection_id"))
                .platform(rs.getString("platform"))
                .shopName(rs.getString("shop_name"))
                .createdBy(rs.getLong("created_by"))
                .name(rs.getString("name"))
                .discountPercent(rs.getBigDecimal("discount_percent"))
                .requiredPoints(rs.getLong("required_points"))
                .quantity(qty)
                .reservedCount(reserved)
                .availableQuantity(Math.max(0, qty - reserved))
                .provisioningMode(rs.getString("provisioning_mode"))
                .externalCouponId(rs.getString("external_coupon_id"))
                .tiktokBatchTitle(rs.getString("tiktok_batch_title"))
                .status(rs.getString("status"))
                .startsAt(toInstant(rs.getTimestamp("starts_at")))
                .endsAt(toInstant(rs.getTimestamp("ends_at")))
                .createdAt(toInstant(rs.getTimestamp("created_at")))
                .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                .build();
    }

    private static void validateCampaignDates(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new BusinessException("endsAt must be after startsAt",
                    HttpStatus.BAD_REQUEST, "INVALID_CAMPAIGN_DATES");
        }
    }

    private static String normalizeMode(String mode) {
        String normalized = (mode == null || mode.isBlank()) ? "import" : mode.trim().toLowerCase(Locale.ROOT);
        if (!VALID_MODES.contains(normalized)) {
            throw new BusinessException("provisioningMode must be 'api' or 'import'",
                    HttpStatus.BAD_REQUEST, "INVALID_PROVISIONING_MODE");
        }
        return normalized;
    }

    private static String normalizeStatus(String status, String fallback) {
        String normalized = (status == null || status.isBlank()) ? fallback : status.trim().toLowerCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            throw new BusinessException("status must be one of draft, active, paused, closed",
                    HttpStatus.BAD_REQUEST, "INVALID_CAMPAIGN_STATUS");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static final String BASE_CAMPAIGN_SELECT =
            "SELECT c.id, c.tenant_id, c.connection_id, mc.platform::text AS platform, mc.shop_name, "
                    + "c.created_by, c.name, c.discount_percent, c.required_points, c.quantity, c.reserved_count, "
                    + "c.provisioning_mode, c.external_coupon_id, c.tiktok_batch_title, c.status, c.starts_at, c.ends_at, c.created_at, c.updated_at "
                    + "FROM loyalty_reward_campaigns c "
                    + "JOIN marketplace_connections mc ON mc.tenant_id = c.tenant_id AND mc.id = c.connection_id";

    private record ConnectionRow(Long id, String platform, String status, String shopName) {
    }
}
