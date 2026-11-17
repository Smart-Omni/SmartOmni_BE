package com.smartomni.integration.service;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.InternalIssueVoucherRequest;
import com.smartomni.integration.dto.InternalIssueVoucherResponse;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketplaceVoucherAdapterServiceTest {

    @Mock
    private MarketplaceConnectionRepository connectionRepository;

    @Mock
    private TiktokVoucherClient tiktokVoucherClient;

    @InjectMocks
    private MarketplaceVoucherAdapterService adapterService;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void tiktokImportModeResolvesOngoingCouponCodeFromSimulator() {
        TenantContext.setTenantId(1L);
        MarketplaceConnection conn = connectedTiktok(10L, 1L);
        when(connectionRepository.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(conn));
        when(tiktokVoucherClient.searchCoupons(10L)).thenReturn(List.of(
                TiktokVoucherDto.builder()
                        .tiktokVoucherId("TT-COUPON-1")
                        .voucherCode("SIMSILVER10")
                        .discountType("PERCENT_OFF")
                        .discountValue(BigDecimal.TEN)
                        .status("ONGOING")
                        .build()));

        InternalIssueVoucherResponse res = adapterService.issueVoucher(
                InternalIssueVoucherRequest.builder()
                        .connectionId(10L)
                        .requestKey("req-1")
                        .title("Silver 10%")
                        .discountPercent(BigDecimal.TEN)
                        .provisioningMode("import")
                        .build());

        assertThat(res.getOutcome()).isEqualTo("ISSUED");
        assertThat(res.getCode()).isEqualTo("SIMSILVER10");
        assertThat(res.getExternalVoucherId()).isEqualTo("TT-COUPON-1");
    }

    @Test
    void tiktokApiModeReturnsFailedOn4xxRejectionAndPendingOn5xxTimeout() {
        TenantContext.setTenantId(1L);
        MarketplaceConnection conn = connectedTiktok(10L, 1L);
        when(connectionRepository.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(conn));

        when(tiktokVoucherClient.createCoupon(eq(10L), any(), any(), any(), any(), eq("req-4xx")))
                .thenThrow(new TiktokApiException("promotion.write missing", 403, 403, "forbidden", false));
        InternalIssueVoucherResponse failed = adapterService.issueVoucher(
                InternalIssueVoucherRequest.builder()
                        .connectionId(10L)
                        .requestKey("req-4xx")
                        .discountPercent(BigDecimal.TEN)
                        .provisioningMode("api")
                        .build());
        assertThat(failed.getOutcome()).isEqualTo("FAILED");
        assertThat(failed.getFailureReason()).contains("TikTok rejected");

        when(tiktokVoucherClient.createCoupon(eq(10L), any(), any(), any(), any(), eq("req-5xx")))
                .thenThrow(new TiktokApiException("gateway timeout", 504, 504, "timeout", true));
        InternalIssueVoucherResponse pending = adapterService.issueVoucher(
                InternalIssueVoucherRequest.builder()
                        .connectionId(10L)
                        .requestKey("req-5xx")
                        .discountPercent(BigDecimal.TEN)
                        .provisioningMode("api")
                        .build());
        assertThat(pending.getOutcome()).isEqualTo("PENDING");
    }

    @Test
    void rejectsDisconnectedConnection() {
        TenantContext.setTenantId(1L);
        MarketplaceConnection conn = connectedTiktok(10L, 1L);
        conn.setStatus(MarketplaceConnection.ConnectionStatus.DISCONNECTED);
        when(connectionRepository.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(conn));

        InternalIssueVoucherResponse res = adapterService.issueVoucher(
                InternalIssueVoucherRequest.builder()
                        .connectionId(10L)
                        .requestKey("req-disc")
                        .provisioningMode("import")
                        .build());

        assertThat(res.getOutcome()).isEqualTo("FAILED");
        assertThat(res.getFailureReason()).contains("CONNECTED");
    }

    private static MarketplaceConnection connectedTiktok(Long id, Long tenantId) {
        MarketplaceConnection c = new MarketplaceConnection();
        c.setId(id);
        c.setTenantId(tenantId);
        c.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        c.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        c.setShopId("7494000000000000001");
        return c;
    }
}
