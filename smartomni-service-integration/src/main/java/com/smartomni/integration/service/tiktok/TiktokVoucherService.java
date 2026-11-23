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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service managing TikTok Shop voucher issuance and coupon sync with PostgreSQL RLS (WF-02 Part A).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokVoucherService {

    private final TiktokTenantConnections tenantConnections;
    private final TiktokVoucherClient voucherClient;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * TikTok has no coupon creation API: codes are created as a batch on Seller Center. Always 501
     * {@code TIKTOK_VOUCHER_CREATE_UNSUPPORTED}; nothing is generated locally anymore.
     */
    public TiktokVoucherDto createVoucher(CreateTiktokVoucherRequest request) {
        throw new BusinessException("TikTok vouchers cannot be created through the API. Create a batch of codes on "
                + "TikTok Seller Center, then sync them with POST /api/v1/tiktok/vouchers/sync?title=<batch title>.",
                HttpStatus.NOT_IMPLEMENTED, "TIKTOK_VOUCHER_CREATE_UNSUPPORTED");
    }

    /**
     * Syncs read-only coupons from TikTok Simulator ({@code POST /promotion/202406/coupons/search}) into DB.
     */
    public List<TiktokVoucherDto> syncCouponsFromSimulator() {
        return syncCouponsFromSimulator(null);
    }

    /** Pulls every coupon (all pages), or only those whose title contains {@code batchTitle}. */
    public List<TiktokVoucherDto> syncCouponsFromSimulator(String batchTitle) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        List<TiktokVoucherDto> coupons = voucherClient.searchCoupons(connection.getId(), batchTitle, null);

        long tenantId = connection.getTenantId();
        String role = currentRole();

        return transactionTemplate.execute(status -> {
            applyRls(tenantId, role);
            List<TiktokVoucherDto> saved = new ArrayList<>();
            for (TiktokVoucherDto coupon : coupons) {
                long id = upsertVoucherRow(tenantId, connection, coupon, null);
                coupon.setId(id);
                coupon.setTenantId(tenantId);
                coupon.setConnectionId(connection.getId());
                coupon.setShopId(connection.getShopId());
                saved.add(coupon);
            }
            return saved;
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

    private long upsertVoucherRow(long tenantId, MarketplaceConnection connection,
                                  TiktokVoucherDto voucher, String idempotencyKey) {
        List<Long> existingIds = jdbcTemplate.query(
                "SELECT id FROM tiktok_vouchers WHERE tenant_id = ? AND connection_id = ? AND tiktok_voucher_id = ?",
                (rs, i) -> rs.getLong("id"),
                tenantId, connection.getId(), voucher.getTiktokVoucherId());

        if (!existingIds.isEmpty()) {
            long existingId = existingIds.get(0);
            jdbcTemplate.update(
                    "UPDATE tiktok_vouchers SET status = ?, redeemed_count = ?, updated_at = now() "
                            + "WHERE tenant_id = ? AND id = ?",
                    voucher.getStatus() != null ? voucher.getStatus() : "ONGOING",
                    voucher.getRedeemedCount() != null ? voucher.getRedeemedCount() : 0,
                    tenantId, existingId);
            return existingId;
        }

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
            ps.setString(4, voucher.getTiktokVoucherId());
            ps.setString(5, voucher.getVoucherCode());
            ps.setString(6, voucher.getTitle());
            ps.setString(7, voucher.getDiscountType());
            ps.setBigDecimal(8, voucher.getDiscountValue());
            ps.setBigDecimal(9, voucher.getMaxDiscountAmount());
            ps.setBigDecimal(10, voucher.getMinOrderAmount());
            if (voucher.getUsageLimit() != null) {
                ps.setInt(11, voucher.getUsageLimit());
            } else {
                ps.setNull(11, java.sql.Types.INTEGER);
            }
            ps.setInt(12, voucher.getRedeemedCount() != null ? voucher.getRedeemedCount() : 0);
            ps.setTimestamp(13, Timestamp.from(voucher.getStartTime()));
            ps.setTimestamp(14, Timestamp.from(voucher.getEndTime()));
            ps.setString(15, voucher.getStatus() != null ? voucher.getStatus() : "ONGOING");
            ps.setString(16, idempotencyKey);
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

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
