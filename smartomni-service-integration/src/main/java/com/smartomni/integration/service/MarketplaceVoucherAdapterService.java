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
import org.springframework.transaction.annotation.Transactional;

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

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokVoucherClient tiktokVoucherClient;

    @Transactional(readOnly = true)
    public InternalIssueVoucherResponse issueVoucher(InternalIssueVoucherRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || request == null || request.getConnectionId() == null) {
            return failed("Missing tenant or connectionId");
        }

        MarketplaceConnection connection = connectionRepository.findByIdAndTenantId(request.getConnectionId(), tenantId)
                .orElse(null);
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

    private InternalIssueVoucherResponse handleTiktok(MarketplaceConnection connection, InternalIssueVoucherRequest req) {
        String mode = req.getProvisioningMode() != null ? req.getProvisioningMode().trim().toLowerCase() : "import";
        try {
            if ("api".equals(mode)) {
                TiktokVoucherDto created = tiktokVoucherClient.createCoupon(
                        connection.getId(),
                        req.getTitle(),
                        req.getDiscountPercent(),
                        req.getStartsAt(),
                        req.getEndsAt(),
                        req.getRequestKey());
                if (created != null && hasText(created.getTiktokVoucherId()) && hasText(created.getVoucherCode())) {
                    return issued(created.getTiktokVoucherId(), created.getVoucherCode());
                }
                return failed("TikTok Shop API did not return valid coupon id and promo code");
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

            List<TiktokVoucherDto> coupons = tiktokVoucherClient.searchCoupons(connection.getId());
            TiktokVoucherDto match = coupons.stream()
                    .filter(c -> hasText(c.getTiktokVoucherId()) && hasText(c.getVoucherCode()))
                    .filter(c -> "ONGOING".equalsIgnoreCase(c.getStatus()) || "NOT_START".equalsIgnoreCase(c.getStatus()))
                    .filter(c -> req.getDiscountPercent() == null
                            || ("PERCENT_OFF".equalsIgnoreCase(c.getDiscountType())
                                && c.getDiscountValue() != null
                                && c.getDiscountValue().compareTo(req.getDiscountPercent()) == 0))
                    .findFirst()
                    .orElseGet(() -> coupons.stream()
                            .filter(c -> hasText(c.getTiktokVoucherId()) && hasText(c.getVoucherCode()))
                            .filter(c -> "ONGOING".equalsIgnoreCase(c.getStatus()))
                            .findFirst()
                            .orElse(null));

            if (match == null) {
                return failed("No active coupon found on TikTok Shop for import mode");
            }
            return issued(match.getTiktokVoucherId(), match.getVoucherCode());
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
