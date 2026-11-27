package com.smartomni.integration.service;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.shopee.ShopeeApiException;
import com.smartomni.integration.client.shopee.ShopeeVoucherClient;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.InternalIssueVoucherRequest;
import com.smartomni.integration.dto.InternalIssueVoucherResponse;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokVoucherBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Marketplace voucher provisioning adapter for WF-02.
 * Verifies/provisions vouchers on the target marketplace (TikTok Shop or Shopee)
 * before a loyalty reward request can transition to {@code issued}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceVoucherAdapterService {

    static final String API_MODE_UNSUPPORTED = "TikTok coupons cannot be created through the API; create a code batch "
            + "on Seller Center and use import mode with the batch title";

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokVoucherClient tiktokVoucherClient;
    private final com.smartomni.integration.service.tiktok.TiktokVoucherStore voucherStore;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final ShopeeVoucherClient shopeeVoucherClient;

    static final String SHOPEE_DUPLICATE_CODE = "voucher.voucher_code_duplicate_error";
    private static final int SHOPEE_CODE_ATTEMPTS = 3;
    /** Shopee caps a voucher's validity at about three months. */
    private static final java.time.Duration SHOPEE_VOUCHER_VALIDITY = java.time.Duration.ofDays(90);

    public InternalIssueVoucherResponse issueVoucher(InternalIssueVoucherRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || request == null || request.getConnectionId() == null) {
            return failed("Missing tenant or connectionId");
        }

        MarketplaceConnection connection = transactionTemplate.execute(status ->
                connectionRepository.findByIdAndTenantId(request.getConnectionId(), tenantId).orElse(null));
        if (connection == null) {
            return failed("Marketplace connection not found for tenant");
        }
        if (connection.getStatus() != MarketplaceConnection.ConnectionStatus.CONNECTED) {
            return failed("Marketplace connection is not CONNECTED (status=" + connection.getStatus() + ")");
        }

        if (connection.getPlatform() == MarketplaceConnection.Platform.TIKTOK_SHOP) {
            return handleTiktok(connection, request);
        }
        if (connection.getPlatform() == MarketplaceConnection.Platform.SHOPEE) {
            return handleShopee(connection, request);
        }
        return failed("Unsupported marketplace platform: " + connection.getPlatform());
    }

    /**
     * TikTok vouchers (Phase 09c): {@code api} mode is refused (no create API); {@code import} hands out one code of
     * the Seller Center batch whose title is configured on the campaign, ONGOING and not handed out before. The
     * same {@code requestKey} always gets the same code back.
     */
    private InternalIssueVoucherResponse handleTiktok(MarketplaceConnection connection, InternalIssueVoucherRequest req) {
        String mode = req.getProvisioningMode() != null ? req.getProvisioningMode().trim().toLowerCase() : "import";
        if ("api".equals(mode)) {
            return failed(API_MODE_UNSUPPORTED);
        }
        long tenantId = connection.getTenantId();
        try {
            if (hasText(req.getRequestKey())) {
                TiktokVoucherDto already = transactionTemplate.execute(status ->
                        voucherStore.findIssued(tenantId, connection.getId(), req.getRequestKey()).orElse(null));
                if (already != null) {
                    return issued(already.getTiktokVoucherId(), already.getVoucherCode());
                }
            }

            // "import" mode: verify an existing coupon on TikTok Shop / Simulator
            if (hasText(req.getExternalCouponId())) {
                return issueConfiguredCoupon(connection, req);
            }

            // The batch the campaign is linked to; campaigns saved before batch linking fall back to their name.
            String batchTitle = hasText(req.getBatchTitle()) ? req.getBatchTitle().trim()
                    : hasText(req.getTitle()) ? req.getTitle().trim() : null;
            if (batchTitle == null) {
                return failed("Import mode needs the TikTok code batch title on the campaign");
            }
            if (!hasText(req.getRequestKey())) {
                return failed("Import mode needs a requestKey to record which code was handed out");
            }
            // Pull the batch (all pages), keep the ONGOING codes titled exactly like the batch, then only those giving
            // the campaign's percent: a code with another value is never handed out.
            List<TiktokVoucherDto> titled = tiktokVoucherClient.searchCoupons(connection.getId(), batchTitle, List.of("ONGOING")).stream()
                    .filter(c -> hasText(c.getTiktokVoucherId()) && hasText(c.getVoucherCode()))
                    .filter(c -> "ONGOING".equalsIgnoreCase(c.getStatus()))
                    .filter(c -> TiktokVoucherBatchService.sameTitle(c.getTitle(), batchTitle))
                    .toList();
            if (titled.isEmpty()) {
                return failed("No ongoing TikTok code batch titled '" + batchTitle + "'");
            }
            List<TiktokVoucherDto> batch = req.getDiscountPercent() == null ? titled : titled.stream()
                    .filter(c -> TiktokVoucherBatchService.samePercent(c, req.getDiscountPercent()))
                    .toList();
            if (batch.isEmpty()) {
                return failed("TikTok batch '" + batchTitle + "' gives " + TiktokVoucherBatchService.describe(titled.get(0))
                        + ", campaign needs " + percent(req.getDiscountPercent()));
            }
            List<String> candidates = batch.stream().map(TiktokVoucherDto::getTiktokVoucherId).toList();
            TiktokVoucherDto claimed = transactionTemplate.execute(status -> {
                for (TiktokVoucherDto c : batch) {
                    voucherStore.upsertPulled(tenantId, connection.getId(), connection.getShopId(), c);
                }
                return voucherStore.claimFree(tenantId, connection.getId(), candidates, req.getRequestKey()).orElse(null);
            });
            if (claimed == null) {
                return failed("No free code left in TikTok batch '" + batchTitle + "'");
            }
            return issued(claimed.getTiktokVoucherId(), claimed.getVoucherCode());
        } catch (TiktokApiException ex) {
            if (ex.getHttpStatus() >= 500 || ex.isTransientFailure()) {
                log.warn("Transient TikTok error provisioning voucher for requestKey={}: {}", req.getRequestKey(), ex.getMessage());
                return pending(ex.getMessage());
            }
            log.warn("Definitive TikTok rejection provisioning voucher for requestKey={}: {}", req.getRequestKey(), ex.getMessage());
            return failed("TikTok rejected voucher provisioning: " + ex.getMessage());
        } catch (Exception ex) {
            log.warn("Ambiguous error provisioning TikTok voucher for requestKey={}: {}", req.getRequestKey(), ex.getMessage());
            return pending(ex.getMessage());
        }
    }

    /**
     * Shopee vouchers: {@code api} mode creates a single-use shop voucher through v2.voucher.add_voucher.
     * The worker retries PENDING outcomes, so creation must be idempotent: the code is derived from the requestKey and
     * the requestKey is written into the voucher name. A duplicate code whose name carries our key is ours already.
     */
    private InternalIssueVoucherResponse handleShopee(MarketplaceConnection connection, InternalIssueVoucherRequest req) {
        String mode = req.getProvisioningMode() != null ? req.getProvisioningMode().trim().toLowerCase() : "api";
        if (!"api".equals(mode)) {
            return failed("Shopee loyalty vouchers use api mode (SmartOmni creates each voucher on Shopee)");
        }
        if (!hasText(req.getRequestKey()) || req.getDiscountPercent() == null
                || req.getDiscountPercent().signum() <= 0 || req.getDiscountPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
            return failed("Shopee voucher needs a requestKey and a discount percent between 1 and 100");
        }
        long start = Instant.now().getEpochSecond();
        long end = start + SHOPEE_VOUCHER_VALIDITY.toSeconds();
        if (req.getEndsAt() != null && req.getEndsAt().getEpochSecond() > start) {
            end = Math.min(end, req.getEndsAt().getEpochSecond());
        }
        String name = shopeeVoucherName(req);
        try {
            for (int attempt = 0; attempt < SHOPEE_CODE_ATTEMPTS; attempt++) {
                String code = shopeeVoucherCode(req.getRequestKey(), attempt);
                try {
                    long voucherId = shopeeVoucherClient.addPercentVoucher(connection.getId(), name, code,
                            req.getDiscountPercent(), start, end);
                    return issued(String.valueOf(voucherId), code);
                } catch (ShopeeApiException e) {
                    if (!SHOPEE_DUPLICATE_CODE.equalsIgnoreCase(e.getErrorCode())) {
                        throw e;
                    }
                    var existing = shopeeVoucherClient.findByCode(connection.getId(), code);
                    if (existing.isPresent() && existing.get().name().contains(req.getRequestKey())) {
                        return issued(String.valueOf(existing.get().voucherId()), existing.get().code());
                    }
                    // Someone else's voucher uses this code: try the next code derived from the same key.
                }
            }
            return failed("Could not find a free Shopee voucher code");
        } catch (ShopeeApiException ex) {
            if (ex.isTransientFailure() || ex.getHttpStatus() >= 500) {
                log.warn("Transient Shopee error provisioning voucher for requestKey={}: {}", req.getRequestKey(), ex.getMessage());
                return pending(ex.getMessage());
            }
            log.warn("Shopee rejected voucher for requestKey={}: {}", req.getRequestKey(), ex.getMessage());
            return failed("Shopee rejected voucher provisioning: " + ex.getMessage());
        } catch (Exception ex) {
            log.warn("Ambiguous error provisioning Shopee voucher for requestKey={}: {}", req.getRequestKey(), ex.getMessage());
            return pending(ex.getMessage());
        }
    }

    /**
     * A campaign pinned to one coupon id. The coupon must give the campaign's percent. A single-use coupon is handed
     * out once (recorded like a batch code); a coupon with a higher or no redemption limit is shared by design.
     */
    private InternalIssueVoucherResponse issueConfiguredCoupon(MarketplaceConnection connection, InternalIssueVoucherRequest req) {
        String couponId = req.getExternalCouponId().trim();
        TiktokVoucherDto detail = tiktokVoucherClient.getCoupon(connection.getId(), couponId);
        if (detail == null || !hasText(detail.getTiktokVoucherId()) || !hasText(detail.getVoucherCode())
                || "DEACTIVATED".equalsIgnoreCase(detail.getStatus()) || "EXPIRED".equalsIgnoreCase(detail.getStatus())) {
            return failed("Configured TikTok coupon is unavailable or expired: " + couponId);
        }
        if (req.getDiscountPercent() != null && !TiktokVoucherBatchService.samePercent(detail, req.getDiscountPercent())) {
            return failed("TikTok coupon " + couponId + " gives " + TiktokVoucherBatchService.describe(detail)
                    + ", campaign needs " + percent(req.getDiscountPercent()));
        }
        if (!Integer.valueOf(1).equals(detail.getUsageLimit())) {
            return issued(detail.getTiktokVoucherId(), detail.getVoucherCode());
        }
        if (!hasText(req.getRequestKey())) {
            return failed("Import mode needs a requestKey to record which code was handed out");
        }
        long tenantId = connection.getTenantId();
        TiktokVoucherDto claimed = transactionTemplate.execute(status -> {
            voucherStore.upsertPulled(tenantId, connection.getId(), connection.getShopId(), detail);
            return voucherStore.claimFree(tenantId, connection.getId(), List.of(detail.getTiktokVoucherId()),
                    req.getRequestKey()).orElse(null);
        });
        if (claimed == null) {
            return failed("Single-use TikTok coupon " + couponId + " was already handed out or used");
        }
        return issued(claimed.getTiktokVoucherId(), claimed.getVoucherCode());
    }

    private static String percent(BigDecimal value) {
        return value == null ? "?" : value.stripTrailingZeros().toPlainString() + "%";
    }

    static String shopeeVoucherName(InternalIssueVoucherRequest req) {
        String title = hasText(req.getTitle()) ? req.getTitle().trim() : "Loyalty";
        return "SmartOmni " + title + " #" + req.getRequestKey();
    }

    /** 5 characters [A-Z0-9] (the simulator's and Shopee's code limit), stable for a requestKey and attempt. */
    static String shopeeVoucherCode(String requestKey, int attempt) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((requestKey + ":" + attempt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            StringBuilder code = new StringBuilder(5);
            for (int i = 0; i < 5; i++) {
                code.append(alphabet.charAt((hash[i] & 0xff) % alphabet.length()));
            }
            return code.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static InternalIssueVoucherResponse issued(String externalId, String code) {
        return InternalIssueVoucherResponse.builder()
                .outcome("ISSUED")
                .externalVoucherId(externalId)
                .code(code)
                .build();
    }

    private static InternalIssueVoucherResponse failed(String reason) {
        return InternalIssueVoucherResponse.builder()
                .outcome("FAILED")
                .failureReason(reason)
                .build();
    }

    private static InternalIssueVoucherResponse pending(String reason) {
        return InternalIssueVoucherResponse.builder()
                .outcome("PENDING")
                .failureReason(reason)
                .build();
    }
}
