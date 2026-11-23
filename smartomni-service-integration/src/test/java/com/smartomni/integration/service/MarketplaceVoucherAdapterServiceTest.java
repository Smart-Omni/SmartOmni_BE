package com.smartomni.integration.service;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.InternalIssueVoucherRequest;
import com.smartomni.integration.dto.InternalIssueVoucherResponse;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokVoucherStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketplaceVoucherAdapterServiceTest {

    private final MarketplaceConnectionRepository connectionRepository = mock(MarketplaceConnectionRepository.class);
    private final TiktokVoucherClient tiktokVoucherClient = mock(TiktokVoucherClient.class);
    private final TiktokVoucherStore store = mock(TiktokVoucherStore.class);
    private MarketplaceVoucherAdapterService adapterService;
    private MarketplaceConnection conn;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        adapterService = new MarketplaceVoucherAdapterService(connectionRepository, tiktokVoucherClient, store,
                new TransactionTemplate(ptm));
        TenantContext.setTenantId(1L);
        conn = connectedTiktok(10L, 1L);
        when(connectionRepository.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(conn));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private static TiktokVoucherDto code(String id, String promo, String title) {
        return TiktokVoucherDto.builder().tiktokVoucherId(id).voucherCode(promo).title(title)
                .discountType("PERCENT_OFF").discountValue(new BigDecimal("30")).status("ONGOING").build();
    }

    private static InternalIssueVoucherRequest importRequest(String key) {
        return InternalIssueVoucherRequest.builder().connectionId(10L).requestKey(key)
                .title("Loyalty Diamond 30%").discountPercent(new BigDecimal("30")).provisioningMode("import").build();
    }

    @Test
    void importModeClaimsAFreeCodeOfTheConfiguredBatchOnly() {
        TiktokVoucherDto diamond1 = code("C1", "DIA-1", "Loyalty Diamond 30%");
        TiktokVoucherDto other = code("C9", "PLAT-9", "Loyalty Platinum 20%");
        when(tiktokVoucherClient.searchCoupons(10L, "Loyalty Diamond 30%", List.of("ONGOING"))).thenReturn(List.of(diamond1, other));
        when(store.claimFree(1L, 10L, List.of("C1"), "req-1")).thenReturn(Optional.of(diamond1));

        InternalIssueVoucherResponse res = adapterService.issueVoucher(importRequest("req-1"));

        assertThat(res.getOutcome()).isEqualTo("ISSUED");
        assertThat(res.getCode()).isEqualTo("DIA-1");
        assertThat(res.getExternalVoucherId()).isEqualTo("C1");
        verify(store).upsertPulled(1L, 10L, conn.getShopId(), diamond1);
        verify(store, never()).upsertPulled(anyLong(), anyLong(), any(), eq(other));
    }

    @Test
    void sameRequestKeyGetsTheSameCodeAndDoesNotClaimAnother() {
        when(store.findIssued(1L, 10L, "req-1")).thenReturn(Optional.of(code("C1", "DIA-1", "Loyalty Diamond 30%")));

        InternalIssueVoucherResponse res = adapterService.issueVoucher(importRequest("req-1"));

        assertThat(res.getCode()).isEqualTo("DIA-1");
        verifyNoInteractions(tiktokVoucherClient);
        verify(store, never()).claimFree(anyLong(), anyLong(), any(), any());
    }

    @Test
    void emptyBatchFails() {
        when(tiktokVoucherClient.searchCoupons(eq(10L), anyString(), any())).thenReturn(List.of());

        InternalIssueVoucherResponse res = adapterService.issueVoucher(importRequest("req-2"));

        assertThat(res.getOutcome()).isEqualTo("FAILED");
        assertThat(res.getFailureReason()).contains("No free code");
    }

    @Test
    void apiModeIsRefusedWithoutCallingTiktok() {
        InternalIssueVoucherResponse res = adapterService.issueVoucher(InternalIssueVoucherRequest.builder()
                .connectionId(10L).requestKey("req-api").discountPercent(BigDecimal.TEN).provisioningMode("api").build());

        assertThat(res.getOutcome()).isEqualTo("FAILED");
        assertThat(res.getFailureReason()).contains("cannot be created through the API");
        verifyNoInteractions(tiktokVoucherClient, store);
    }

    @Test
    void transientTiktokFailureIsPendingAndRejectionIsFailed() {
        when(tiktokVoucherClient.searchCoupons(eq(10L), anyString(), any()))
                .thenThrow(new TiktokApiException("gateway timeout", 504, 504, "timeout", true));
        assertThat(adapterService.issueVoucher(importRequest("req-5xx")).getOutcome()).isEqualTo("PENDING");

        reset(tiktokVoucherClient);
        when(tiktokVoucherClient.searchCoupons(eq(10L), anyString(), any()))
                .thenThrow(new TiktokApiException("scope missing", 403, 403, "forbidden", false));
        InternalIssueVoucherResponse failed = adapterService.issueVoucher(importRequest("req-4xx"));
        assertThat(failed.getOutcome()).isEqualTo("FAILED");
        assertThat(failed.getFailureReason()).contains("TikTok rejected");
    }

    @Test
    void rejectsDisconnectedConnection() {
        conn.setStatus(MarketplaceConnection.ConnectionStatus.DISCONNECTED);

        InternalIssueVoucherResponse res = adapterService.issueVoucher(importRequest("req-disc"));

        assertThat(res.getOutcome()).isEqualTo("FAILED");
        assertThat(res.getFailureReason()).contains("CONNECTED");
    }

    private static MarketplaceConnection connectedTiktok(Long id, Long tenantId) {
        MarketplaceConnection c = new MarketplaceConnection();
        c.setId(id);
        c.setTenantId(tenantId);
        c.setShopId("SHOP-1");
        c.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        c.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        return c;
    }
}
