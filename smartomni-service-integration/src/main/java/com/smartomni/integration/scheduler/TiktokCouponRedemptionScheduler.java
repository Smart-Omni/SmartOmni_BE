package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokVoucherStore;
import com.smartomni.integration.service.tiktok.TiktokVoucherStore.IssuedCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks usage of TikTok codes SmartOmni handed out (simulator Phase 09c, step 4). For every issued code that is
 * still ONGOING and unused it reads {@code GET /promotion/202406/coupons/{id}} and stores {@code status} and
 * {@code usage_stats.redeemed_count}. A code leaves the polled set for good once it is used
 * ({@code redeemed_count >= 1}), DEACTIVATED or EXPIRED, so the set only shrinks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokCouponRedemptionScheduler {

    private final MarketplaceConnectionRepository connections;
    private final TiktokVoucherClient voucherClient;
    private final TiktokVoucherStore store;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${smartomni.tiktok.coupon-redemption-delay-ms:900000}",
            initialDelayString = "${smartomni.tiktok.coupon-redemption-initial-delay-ms:180000}")
    public void pollScheduled() {
        trackAll();
    }

    /** @return number of codes whose usage was refreshed */
    public int trackAll() {
        List<MarketplaceConnection> active;
        // Cross-tenant read on marketplace_connections is allowed to price_sync_worker only (V6).
        try (var scope = TenantContext.openScope(null, "price_sync_worker", null)) {
            active = transactionTemplate.execute(status ->
                    connections.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED));
        }
        if (active == null) return 0;
        Set<Long> tenants = new LinkedHashSet<>();
        for (MarketplaceConnection c : active) {
            if (c.getPlatform() == MarketplaceConnection.Platform.TIKTOK_SHOP && c.getTenantId() != null) {
                tenants.add(c.getTenantId());
            }
        }
        int refreshed = 0;
        for (Long tenantId : tenants) {
            try {
                refreshed += trackTenant(tenantId);
            } catch (Exception ex) {
                log.warn("TikTok coupon tracking failed for tenant {}: {}", tenantId, ex.getMessage());
            }
        }
        return refreshed;
    }

    public int trackTenant(long tenantId) {
        try (var scope = TenantContext.openScope(tenantId, "tenant_admin", null)) {
            List<IssuedCode> open = transactionTemplate.execute(status -> store.issuedOpen(tenantId));
            if (open == null) return 0;
            int refreshed = 0;
            for (IssuedCode code : open) {
                try {
                    TiktokVoucherDto coupon = voucherClient.getCoupon(code.connectionId(), code.couponId());
                    if (coupon == null) continue;
                    String status = coupon.getStatus() != null ? coupon.getStatus() : "ONGOING";
                    int redeemed = coupon.getRedeemedCount() != null ? coupon.getRedeemedCount() : 0;
                    transactionTemplate.executeWithoutResult(s -> store.updateUsage(tenantId, code.id(), status, redeemed));
                    refreshed++;
                    if (redeemed >= 1 || "DEACTIVATED".equals(status) || "EXPIRED".equals(status)) {
                        log.info("TikTok code {} is final (status={}, redeemed={}); no longer polled", code.couponId(), status, redeemed);
                    }
                } catch (Exception ex) {
                    log.warn("Could not read TikTok coupon {}: {}", code.couponId(), ex.getMessage());
                }
            }
            return refreshed;
        }
    }
}
