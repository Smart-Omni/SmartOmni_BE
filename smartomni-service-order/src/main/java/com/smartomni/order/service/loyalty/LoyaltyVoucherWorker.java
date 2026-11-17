package com.smartomni.order.service.loyalty;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherRequest;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Asynchronous worker that confirms voucher provisioning with the marketplace adapter
 * ({@code smartomni-service-integration}) before marking {@code loyalty_reward_requests} as {@code issued}.
 * On definitive failure, marks {@code failed} (DB trigger releases the reserved slot).
 * On ambiguous/transient failure, leaves {@code pending} for reconciliation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyVoucherWorker {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final IntegrationVoucherClient integrationVoucherClient;

    @Async
    public void processRequestAsync(Long tenantId, Long requestId) {
        processPendingRequest(tenantId, requestId);
    }

    public void processPendingRequest(Long tenantId, Long requestId) {
        if (tenantId == null || requestId == null) {
            return;
        }
        TenantContext.setTenantId(tenantId);
        TenantContext.setCurrentRole("TENANT_ADMIN");
        try {
            PendingRewardRow row = transactionTemplate.execute(status -> loadPendingRow(tenantId, requestId));
            if (row == null) {
                return;
            }

            IntegrationIssueVoucherResponse result = integrationVoucherClient.issueVoucher(
                    tenantId,
                    IntegrationIssueVoucherRequest.builder()
                            .connectionId(row.connectionId())
                            .requestKey(row.requestKey())
                            .title(row.campaignName())
                            .discountPercent(row.discountPercent())
                            .provisioningMode(row.provisioningMode())
                            .externalCouponId(row.externalCouponId())
                            .startsAt(row.startsAt())
                            .endsAt(row.endsAt())
                            .build());

            if (result == null || "PENDING".equalsIgnoreCase(result.getOutcome())) {
                log.warn("Voucher provisioning remained PENDING for reconciliation: tenantId={}, requestId={}, reason={}",
                        tenantId, requestId, result != null ? result.getFailureReason() : "null");
                return;
            }

            transactionTemplate.executeWithoutResult(status -> {
                if ("ISSUED".equalsIgnoreCase(result.getOutcome())
                        && result.getCode() != null && !result.getCode().isBlank()) {
                    int updated = jdbcTemplate.update(
                            "UPDATE loyalty_reward_requests "
                                    + "SET status = 'issued', code = ?, external_voucher_id = ?, "
                                    + "failure_reason = NULL, issued_at = now() "
                                    + "WHERE tenant_id = ? AND id = ? AND status = 'pending'",
                            result.getCode().trim(),
                            result.getExternalVoucherId(),
                            tenantId,
                            requestId);
                    if (updated > 0) {
                        String payloadJson = String.format(
                                "{\"requestId\":%d,\"code\":\"%s\",\"externalVoucherId\":\"%s\",\"campaignName\":\"%s\"}",
                                requestId,
                                escapeJson(result.getCode().trim()),
                                escapeJson(result.getExternalVoucherId() != null ? result.getExternalVoucherId() : ""),
                                escapeJson(row.campaignName()));
                        jdbcTemplate.update(
                                "INSERT INTO loyalty_notification_events "
                                        + "(tenant_id, reward_request_id, customer_id, event_type, status, payload) "
                                        + "VALUES (?, ?, ?, 'LOYALTY_VOUCHER_ISSUED', 'pending', ?::jsonb) "
                                        + "ON CONFLICT (tenant_id, reward_request_id, event_type) DO NOTHING",
                                tenantId, requestId, row.customerId(), payloadJson);
                    }
                } else {
                    String reason = result.getFailureReason() != null && !result.getFailureReason().isBlank()
                            ? result.getFailureReason()
                            : "Marketplace voucher provisioning failed";
                    jdbcTemplate.update(
                            "UPDATE loyalty_reward_requests "
                                    + "SET status = 'failed', failure_reason = ? "
                                    + "WHERE tenant_id = ? AND id = ? AND status = 'pending'",
                            reason, tenantId, requestId);
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    private PendingRewardRow loadPendingRow(Long tenantId, Long requestId) {
        List<PendingRewardRow> rows = jdbcTemplate.query(
                "SELECT r.id, r.customer_id, r.request_key::text AS request_key, "
                        + "c.connection_id, c.name AS campaign_name, c.discount_percent, "
                        + "c.provisioning_mode, c.external_coupon_id, c.starts_at, c.ends_at "
                        + "FROM loyalty_reward_requests r "
                        + "JOIN loyalty_reward_campaigns c ON c.tenant_id = r.tenant_id AND c.id = r.campaign_id "
                        + "WHERE r.tenant_id = ? AND r.id = ? AND r.status = 'pending'",
                (rs, rowNum) -> new PendingRewardRow(
                        rs.getLong("id"),
                        rs.getLong("customer_id"),
                        rs.getString("request_key"),
                        rs.getLong("connection_id"),
                        rs.getString("campaign_name"),
                        rs.getBigDecimal("discount_percent"),
                        rs.getString("provisioning_mode"),
                        rs.getString("external_coupon_id"),
                        toInstant(rs.getTimestamp("starts_at")),
                        toInstant(rs.getTimestamp("ends_at"))),
                tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record PendingRewardRow(
            Long requestId,
            Long customerId,
            String requestKey,
            Long connectionId,
            String campaignName,
            BigDecimal discountPercent,
            String provisioningMode,
            String externalCouponId,
            Instant startsAt,
            Instant endsAt) {
    }
}
