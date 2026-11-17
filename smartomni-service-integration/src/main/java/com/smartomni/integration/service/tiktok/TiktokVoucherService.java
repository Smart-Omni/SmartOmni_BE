package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.tiktok.CreateTiktokVoucherRequest;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Service managing TikTok Shop voucher issuance and persistence with PostgreSQL RLS (WF-02 Part A).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokVoucherService {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokVoucherClient voucherClient;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TiktokVoucherDto createVoucher(CreateTiktokVoucherRequest request) {
        validateRequest(request);
        MarketplaceConnection connection = tenantConnections.requireConnected();

        Instant startTime = request.getStartTime() != null ? request.getStartTime() : Instant.now();
        Instant endTime = request.getEndTime() != null ? request.getEndTime() : startTime.plus(30, ChronoUnit.DAYS);
        if (!endTime.isAfter(startTime)) {
            throw new BusinessException("endTime must be after startTime", HttpStatus.BAD_REQUEST, "INVALID_VOUCHER_DATES");
        }

        CreateTiktokVoucherRequest normalized = CreateTiktokVoucherRequest.builder()
                .title(request.getTitle().trim())
                .discountType(request.getDiscountType().trim().toUpperCase(Locale.ROOT))
                .discountValue(request.getDiscountValue())
                .maxDiscountAmount(request.getMaxDiscountAmount())
                .minOrderAmount(request.getMinOrderAmount())
                .usageLimit(request.getUsageLimit())
                .code(request.getCode())
                .customerId(request.getCustomerId())
                .startTime(startTime)
                .endTime(endTime)
                .idempotencyKey(request.getIdempotencyKey())
                .build();

        TiktokVoucherDto remote = voucherClient.createVoucher(connection.getId(), normalized);
        if (remote == null || remote.getTiktokVoucherId() == null || remote.getTiktokVoucherId().isBlank()) {
            throw new BusinessException("TikTok Simulator did not return a voucher_id",
                    HttpStatus.BAD_GATEWAY, "VOUCHER_CREATION_FAILED");
        }

        long tenantId = connection.getTenantId();
        String role = currentRole();

        return transactionTemplate.execute(status -> {
            applyRls(tenantId, role);

            List<Long> existingIds = jdbcTemplate.query(
                    "SELECT id FROM tiktok_vouchers WHERE tenant_id = ? AND connection_id = ? AND tiktok_voucher_id = ?",
                    (rs, i) -> rs.getLong("id"),
                    tenantId, connection.getId(), remote.getTiktokVoucherId());

            long voucherDbId;
            if (existingIds.isEmpty()) {
                voucherDbId = insertVoucher(tenantId, connection, remote, normalized);
            } else {
                voucherDbId = existingIds.get(0);
                jdbcTemplate.update(
                        "UPDATE tiktok_vouchers SET status = ?, redeemed_count = ?, updated_at = now() "
                                + "WHERE tenant_id = ? AND id = ?",
                        remote.getStatus() != null ? remote.getStatus() : "ONGOING",
                        remote.getRedeemedCount() != null ? remote.getRedeemedCount() : 0,
                        tenantId, voucherDbId);
            }

            return TiktokVoucherDto.builder()
                    .id(voucherDbId)
                    .tenantId(tenantId)
                    .connectionId(connection.getId())
                    .shopId(connection.getShopId())
                    .tiktokVoucherId(remote.getTiktokVoucherId())
                    .voucherCode(remote.getVoucherCode())
                    .title(remote.getTitle() != null ? remote.getTitle() : normalized.getTitle())
                    .discountType(remote.getDiscountType() != null ? remote.getDiscountType() : normalized.getDiscountType())
                    .discountValue(remote.getDiscountValue() != null ? remote.getDiscountValue() : normalized.getDiscountValue())
                    .maxDiscountAmount(remote.getMaxDiscountAmount())
                    .minOrderAmount(remote.getMinOrderAmount())
                    .usageLimit(remote.getUsageLimit())
                    .redeemedCount(remote.getRedeemedCount() != null ? remote.getRedeemedCount() : 0)
                    .startTime(remote.getStartTime() != null ? remote.getStartTime() : startTime)
                    .endTime(remote.getEndTime() != null ? remote.getEndTime() : endTime)
                    .status(remote.getStatus() != null ? remote.getStatus() : "ONGOING")
                    .createdAt(Instant.now())
                    .build();
        });
    }

    public List<TiktokVoucherDto> listVouchers() {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        long tenantId = connection.getTenantId();
        String role = currentRole();

        return transactionTemplate.execute(status -> {
            applyRls(tenantId, role);
            return jdbcTemplate.query(
                    "SELECT id, tenant_id, connection_id, shop_id, tiktok_voucher_id, voucher_code, title, "
                            + "discount_type, discount_value, max_discount_amount, min_order_amount, usage_limit, "
                            + "redeemed_count, start_time, end_time, status, created_at "
                            + "FROM tiktok_vouchers WHERE tenant_id = ? ORDER BY created_at DESC, id DESC",
                    (rs, i) -> TiktokVoucherDto.builder()
                            .id(rs.getLong("id"))
                            .tenantId(rs.getLong("tenant_id"))
                            .connectionId(rs.getLong("connection_id"))
                            .shopId(rs.getString("shop_id"))
                            .tiktokVoucherId(rs.getString("tiktok_voucher_id"))
                            .voucherCode(rs.getString("voucher_code"))
                            .title(rs.getString("title"))
                            .discountType(rs.getString("discount_type"))
                            .discountValue(rs.getBigDecimal("discount_value"))
                            .maxDiscountAmount(rs.getBigDecimal("max_discount_amount"))
                            .minOrderAmount(rs.getBigDecimal("min_order_amount"))
                            .usageLimit((Integer) rs.getObject("usage_limit"))
                            .redeemedCount(rs.getInt("redeemed_count"))
                            .startTime(toInstant(rs.getTimestamp("start_time")))
                            .endTime(toInstant(rs.getTimestamp("end_time")))
                            .status(rs.getString("status"))
                            .createdAt(toInstant(rs.getTimestamp("created_at")))
                            .build(),
                    tenantId);
        });
    }

    private long insertVoucher(long tenantId, MarketplaceConnection connection,
                               TiktokVoucherDto remote, CreateTiktokVoucherRequest req) {
        String sql = "INSERT INTO tiktok_vouchers (tenant_id, connection_id, shop_id, tiktok_voucher_id, voucher_code, "
                + "title, discount_type, discount_value, max_discount_amount, min_order_amount, usage_limit, "
                + "redeemed_count, start_time, end_time, status, idempotency_key, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())";

        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, tenantId);
            ps.setLong(2, connection.getId());
            ps.setString(3, connection.getShopId());
            ps.setString(4, remote.getTiktokVoucherId());
            ps.setString(5, remote.getVoucherCode());
            ps.setString(6, remote.getTitle() != null ? remote.getTitle() : req.getTitle());
            ps.setString(7, remote.getDiscountType() != null ? remote.getDiscountType() : req.getDiscountType());
            ps.setBigDecimal(8, remote.getDiscountValue() != null ? remote.getDiscountValue() : req.getDiscountValue());
            ps.setBigDecimal(9, remote.getMaxDiscountAmount());
            ps.setBigDecimal(10, remote.getMinOrderAmount());
            if (remote.getUsageLimit() != null) {
                ps.setInt(11, remote.getUsageLimit());
            } else {
                ps.setNull(11, java.sql.Types.INTEGER);
            }
            ps.setInt(12, remote.getRedeemedCount() != null ? remote.getRedeemedCount() : 0);
            ps.setTimestamp(13, Timestamp.from(remote.getStartTime() != null ? remote.getStartTime() : req.getStartTime()));
            ps.setTimestamp(14, Timestamp.from(remote.getEndTime() != null ? remote.getEndTime() : req.getEndTime()));
            ps.setString(15, remote.getStatus() != null ? remote.getStatus() : "ONGOING");
            ps.setString(16, req.getIdempotencyKey());
            return ps;
        }, keys);

        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert into tiktok_vouchers did not return id");
        }
        return key.longValue();
    }

    private void applyRls(long tenantId, String role) {
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, Long.toString(tenantId));
        jdbcTemplate.queryForObject("SELECT set_config('app.user_role', ?, true)", String.class, role);
    }

    private static String currentRole() {
        String role = TenantContext.getCurrentRole();
        return (role == null || role.isBlank()) ? "tenant_admin" : role.toLowerCase(Locale.ROOT);
    }

    private static void validateRequest(CreateTiktokVoucherRequest request) {
        if (request == null) {
            throw new BusinessException("Voucher request body is required", HttpStatus.BAD_REQUEST, "INVALID_VOUCHER_REQUEST");
        }
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BusinessException("Voucher title is required", HttpStatus.BAD_REQUEST, "INVALID_VOUCHER_TITLE");
        }
        if (request.getDiscountType() == null || request.getDiscountType().isBlank()) {
            throw new BusinessException("discountType is required (PERCENT_OFF or AMOUNT_OFF)",
                    HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_TYPE");
        }
        String type = request.getDiscountType().trim().toUpperCase(Locale.ROOT);
        if (!"PERCENT_OFF".equals(type) && !"AMOUNT_OFF".equals(type)) {
            throw new BusinessException("discountType must be PERCENT_OFF or AMOUNT_OFF",
                    HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_TYPE");
        }
        if (request.getDiscountValue() == null || request.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("discountValue must be greater than 0",
                    HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_VALUE");
        }
        if ("PERCENT_OFF".equals(type) && request.getDiscountValue().compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("discountValue for PERCENT_OFF must be between 1 and 100",
                    HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_VALUE");
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
