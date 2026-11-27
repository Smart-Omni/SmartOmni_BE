package com.smartomni.integration.service.tiktok;

import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code tiktok_vouchers} as the pool of single-use TikTok codes pulled from Seller Center batches.
 *
 * <p>"Handed out" needs no new column: a code is issued when its {@code idempotency_key} holds the loyalty
 * {@code requestKey} that received it. That makes issuing idempotent (the same request gets the same code back)
 * and a code can never be handed out twice. Every method needs the tenant's RLS context in an open transaction.
 */
@Repository
@RequiredArgsConstructor
public class TiktokVoucherStore {

    /** A code handed out and still worth polling for usage. */
    public record IssuedCode(long id, long connectionId, String couponId) { }

    private final JdbcTemplate jdbc;

    /** The code already issued to {@code requestKey}, if any. */
    public Optional<TiktokVoucherDto> findIssued(long tenantId, long connectionId, String requestKey) {
        return jdbc.query("SELECT tiktok_voucher_id, voucher_code FROM tiktok_vouchers "
                        + "WHERE tenant_id = ? AND connection_id = ? AND idempotency_key = ?",
                (rs, i) -> TiktokVoucherDto.builder().tiktokVoucherId(rs.getString(1)).voucherCode(rs.getString(2)).build(),
                tenantId, connectionId, requestKey).stream().findFirst();
    }

    /**
     * Inserts a pulled code, or refreshes status and redeemed count; never touches who it was issued to.
     * Codes the table cannot hold (no code, unknown discount type, non-positive discount) are skipped.
     */
    public void upsertPulled(long tenantId, long connectionId, String shopId, TiktokVoucherDto c) {
        if (c.getVoucherCode() == null || c.getDiscountValue() == null || c.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0
                || !("PERCENT_OFF".equals(c.getDiscountType()) || "AMOUNT_OFF".equals(c.getDiscountType()))) {
            return;
        }
        jdbc.update("""
                INSERT INTO tiktok_vouchers (tenant_id, connection_id, shop_id, tiktok_voucher_id, voucher_code, title,
                    discount_type, discount_value, max_discount_amount, min_order_amount, usage_limit, redeemed_count,
                    start_time, end_time, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (connection_id, tiktok_voucher_id) DO UPDATE
                SET status = EXCLUDED.status,
                    redeemed_count = GREATEST(tiktok_vouchers.redeemed_count, EXCLUDED.redeemed_count), updated_at = now()
                """, tenantId, connectionId, shopId, c.getTiktokVoucherId(), c.getVoucherCode(), c.getTitle(),
                c.getDiscountType(), c.getDiscountValue(), c.getMaxDiscountAmount(), c.getMinOrderAmount(),
                c.getUsageLimit(), c.getRedeemedCount() != null ? c.getRedeemedCount() : 0,
                Timestamp.from(orNow(c.getStartTime())), Timestamp.from(orNow(c.getEndTime())),
                c.getStatus() != null ? c.getStatus() : "ONGOING");
    }

    /**
     * Atomically hands one free code out of {@code candidateCouponIds} (ONGOING, unused, not issued) to
     * {@code requestKey}. Concurrent claims skip each other's locked rows.
     */
    public Optional<TiktokVoucherDto> claimFree(long tenantId, long connectionId, List<String> candidateCouponIds, String requestKey) {
        if (candidateCouponIds == null || candidateCouponIds.isEmpty()) {
            return Optional.empty();
        }
        String placeholders = String.join(",", Collections.nCopies(candidateCouponIds.size(), "?"));
        Object[] args = new Object[4 + candidateCouponIds.size()];
        args[0] = requestKey;
        args[1] = tenantId;
        args[2] = connectionId;
        for (int i = 0; i < candidateCouponIds.size(); i++) {
            args[3 + i] = candidateCouponIds.get(i);
        }
        args[args.length - 1] = tenantId;
        return jdbc.query("UPDATE tiktok_vouchers SET idempotency_key = ?, updated_at = now() WHERE id = ("
                        + "SELECT id FROM tiktok_vouchers WHERE tenant_id = ? AND connection_id = ? "
                        + "AND tiktok_voucher_id IN (" + placeholders + ") "
                        + "AND idempotency_key IS NULL AND status = 'ONGOING' AND redeemed_count = 0 AND voucher_code IS NOT NULL "
                        + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED) AND tenant_id = ? "
                        + "RETURNING tiktok_voucher_id, voucher_code",
                (rs, i) -> TiktokVoucherDto.builder().tiktokVoucherId(rs.getString(1)).voucherCode(rs.getString(2)).build(),
                args).stream().findFirst();
    }

    /** Issued codes that are not final yet (ONGOING and unused): the only ones worth polling. */
    public List<IssuedCode> issuedOpen(long tenantId) {
        return jdbc.query("SELECT id, connection_id, tiktok_voucher_id FROM tiktok_vouchers "
                        + "WHERE tenant_id = ? AND idempotency_key IS NOT NULL AND status = 'ONGOING' AND redeemed_count = 0 "
                        + "ORDER BY id",
                (rs, i) -> new IssuedCode(rs.getLong(1), rs.getLong(2), rs.getString(3)), tenantId);
    }

    /** Coupon ids of the connection that can still be handed out (ONGOING, unused, not issued). */
    public Set<String> freeCouponIds(long tenantId, long connectionId) {
        return new HashSet<>(jdbc.query("SELECT tiktok_voucher_id FROM tiktok_vouchers "
                        + "WHERE tenant_id = ? AND connection_id = ? AND idempotency_key IS NULL "
                        + "AND status = 'ONGOING' AND redeemed_count = 0 AND voucher_code IS NOT NULL",
                (rs, i) -> rs.getString(1), tenantId, connectionId));
    }

    public void updateUsage(long tenantId, long id, String status, int redeemedCount) {
        jdbc.update("UPDATE tiktok_vouchers SET status = ?, redeemed_count = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                status, redeemedCount, tenantId, id);
    }

    private static Instant orNow(Instant value) {
        return value != null ? value : Instant.now();
    }
}
