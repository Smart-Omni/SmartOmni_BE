package com.smartomni.integration.service;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.InternalIssueVoucherRequest;
import com.smartomni.integration.dto.InternalIssueVoucherResponse;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


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
                TiktokVoucherDto detail = tiktokVoucherClient.getCoupon(connection.getId(), req.getExternalCouponId().trim());
                if (detail != null && hasText(detail.getTiktokVoucherId()) && hasText(detail.getVoucherCode())
                        && !"DEACTIVATED".equalsIgnoreCase(detail.getStatus())
                        && !"EXPIRED".equalsIgnoreCase(detail.getStatus())) {
                    return issued(detail.getTiktokVoucherId(), detail.getVoucherCode());
                }
                return failed("Configured TikTok coupon is unavailable or expired: " + req.getExternalCouponId());
            }

            if (!hasText(req.getTitle())) {
                return failed("Import mode needs the TikTok code batch title on the campaign");
            }
            if (!hasText(req.getRequestKey())) {
                return failed("Import mode needs a requestKey to record which code was handed out");
            }
            // Pull the batch (all pages), keep only codes of that batch that are ONGOING, then claim a free one.
            String batchTitle = req.getTitle().trim();
            List<TiktokVoucherDto> batch = tiktokVoucherClient.searchCoupons(connection.getId(), batchTitle, List.of("ONGOING")).stream()
                    .filter(c -> hasText(c.getTiktokVoucherId()) && hasText(c.getVoucherCode()))
                    .filter(c -> "ONGOING".equalsIgnoreCase(c.getStatus()))
                    .filter(c -> c.getTitle() != null && c.getTitle().toLowerCase().contains(batchTitle.toLowerCase()))
                    .toList();
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

    private InternalIssueVoucherResponse handleShopee(MarketplaceConnection connection, InternalIssueVoucherRequest req) {
        String mode = req.getProvisioningMode() != null ? req.getProvisioningMode().trim().toLowerCase() : "import";
        if ("import".equals(mode) && hasText(req.getExternalCouponId())) {
            return failed("Shopee voucher import verification is not configured for shop " + connection.getShopId());
        }
        return failed("Shopee voucher creation API is not enabled for this app; configure a verified Shopee voucher");
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
