package com.smartomni.integration.service;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.shopee.ShopeeVoucherClient;
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
    private final ShopeeVoucherClient shopeeVoucherClient = mock(ShopeeVoucherClient.class);
    private MarketplaceVoucherAdapterService adapterService;
    private MarketplaceConnection conn;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        adapterService = new MarketplaceVoucherAdapterService(connectionRepository, tiktokVoucherClient, store,
                new TransactionTemplate(ptm), shopeeVoucherClient);
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
        assertThat(res.getFailureReason()).contains("No ongoing TikTok code batch titled 'Loyalty Diamond 30%'");
    }

    @Test
    void allCodesOfTheBatchHandedOutFails() {
        TiktokVoucherDto diamond1 = code("C1", "DIA-1", "Loyalty Diamond 30%");
        when(tiktokVoucherClient.searchCoupons(eq(10L), anyString(), any())).thenReturn(List.of(diamond1));
        when(store.claimFree(1L, 10L, List.of("C1"), "req-full")).thenReturn(Optional.empty());

        InternalIssueVoucherResponse res = adapterService.issueVoucher(importRequest("req-full"));

        assertThat(res.getOutcome()).isEqualTo("FAILED");
        assertThat(res.getFailureReason()).contains("No free code left");
    }

    @Test
    void linkedBatchTitleIsMatchedExactlyAndWinsOverTheCampaignName() {
        TiktokVoucherDto gold = code("G1", "GOLD-1", "Loyalty Gold 30%");
        TiktokVoucherDto goldPlus = code("G9", "PLUS-9", "Loyalty Gold 30% Plus");
        when(tiktokVoucherClient.searchCoupons(10L, "loyalty gold 30%", List.of("ONGOING"))).thenReturn(List.of(goldPlus, gold));
        when(store.claimFree(1L, 10L, List.of("G1"), "req-g")).thenReturn(Optional.of(gold));

        InternalIssueVoucherResponse res = adapterService.issueVoucher(InternalIssueVoucherRequest.builder()
                .connectionId(10L).requestKey("req-g").title("Gold").batchTitle(" loyalty gold 30% ")
                .discountPercent(new BigDecimal("30.00")).provisioningMode("import").build());

        assertThat(res.getOutcome()).isEqualTo("ISSUED");
        assertThat(res.getCode()).isEqualTo("GOLD-1");
        verify(tiktokVoucherClient).searchCoupons(10L, "loyalty gold 30%", List.of("ONGOING"));
    }

    @Test
    void batchWithAnotherPercentIsNeverHandedOut() {
        TiktokVoucherDto tenPercent = code("G1", "GOLD-1", "Loyalty Gold").toBuilder().discountValue(BigDecimal.TEN).build();
        when(tiktokVoucherClient.searchCoupons(eq(10L), anyString(), any())).thenReturn(List.of(tenPercent));

        InternalIssueVoucherResponse res = adapterService.issueVoucher(InternalIssueVoucherRequest.builder()
                .connectionId(10L).requestKey("req-pct").title("Gold").batchTitle("Loyalty Gold")
                .discountPercent(new BigDecimal("30")).provisioningMode("import").build());

        assertThat(res.getOutcome()).isEqualTo("FAILED");
        assertThat(res.getFailureReason()).isEqualTo("TikTok batch 'Loyalty Gold' gives 10%, campaign needs 30%");
        verify(store, never()).claimFree(anyLong(), anyLong(), any(), any());
    }

    @Test
    void configuredSingleUseCouponIsHandedOutOnlyOnce() {
        TiktokVoucherDto single = code("C7", "ONE-7", "One shot").toBuilder().usageLimit(1).build();
        when(tiktokVoucherClient.getCoupon(10L, "C7")).thenReturn(single);
        when(store.claimFree(1L, 10L, List.of("C7"), "req-a")).thenReturn(Optional.of(single));
        when(store.claimFree(1L, 10L, List.of("C7"), "req-b")).thenReturn(Optional.empty());

        InternalIssueVoucherRequest.InternalIssueVoucherRequestBuilder req = InternalIssueVoucherRequest.builder()
                .connectionId(10L).externalCouponId("C7").discountPercent(new BigDecimal("30")).provisioningMode("import");

        assertThat(adapterService.issueVoucher(req.requestKey("req-a").build()).getOutcome()).isEqualTo("ISSUED");
        InternalIssueVoucherResponse second = adapterService.issueVoucher(req.requestKey("req-b").build());
        assertThat(second.getOutcome()).isEqualTo("FAILED");
        assertThat(second.getFailureReason()).contains("already handed out");
    }

    @Test
    void configuredCouponWithAnotherPercentFails() {
        when(tiktokVoucherClient.getCoupon(10L, "C8")).thenReturn(code("C8", "SHARED", "Shared").toBuilder()
                .discountValue(new BigDecimal("15")).build());

        InternalIssueVoucherResponse res = adapterService.issueVoucher(InternalIssueVoucherRequest.builder()
                .connectionId(10L).requestKey("req-c").externalCouponId("C8").discountPercent(new BigDecimal("30"))
                .provisioningMode("import").build());

        assertThat(res.getFailureReason()).isEqualTo("TikTok coupon C8 gives 15%, campaign needs 30%");
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

    // ---- Shopee (api mode: SmartOmni creates each voucher with v2.voucher.add_voucher)

    private InternalIssueVoucherRequest shopeeRequest(String key) {
        MarketplaceConnection shopee = connectedTiktok(20L, 1L);
        shopee.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        when(connectionRepository.findByIdAndTenantId(20L, 1L)).thenReturn(Optional.of(shopee));
        return InternalIssueVoucherRequest.builder().connectionId(20L).requestKey(key).title("Gold")
                .discountPercent(new BigDecimal("10.00")).provisioningMode("api").build();
    }

    @Test
    void shopeeCreatesAPercentVoucherWithACodeDerivedFromTheRequestKey() {
        String code = MarketplaceVoucherAdapterService.shopeeVoucherCode("req-s1", 0);
        when(shopeeVoucherClient.addPercentVoucher(eq(20L), eq("SmartOmni Gold #req-s1"), eq(code), eq(new BigDecimal("10.00")),
                anyLong(), anyLong())).thenReturn(777L);

        InternalIssueVoucherResponse res = adapterService.issueVoucher(shopeeRequest("req-s1"));

        assertThat(res.getOutcome()).isEqualTo("ISSUED");
        assertThat(res.getCode()).isEqualTo(code).matches("[A-Z0-9]{5}");
        assertThat(res.getExternalVoucherId()).isEqualTo("777");
        assertThat(MarketplaceVoucherAdapterService.shopeeVoucherCode("req-s1", 0)).isEqualTo(code);
    }

    @Test
    void shopeeRetryAfterTimeoutReturnsTheVoucherAlreadyCreated() {
        String code = MarketplaceVoucherAdapterService.shopeeVoucherCode("req-s2", 0);
        when(shopeeVoucherClient.addPercentVoucher(eq(20L), anyString(), eq(code), any(), anyLong(), anyLong()))
                .thenThrow(new com.smartomni.integration.client.shopee.ShopeeApiException("dup", 200,
                        MarketplaceVoucherAdapterService.SHOPEE_DUPLICATE_CODE, "r", false));
        when(shopeeVoucherClient.findByCode(20L, code)).thenReturn(Optional.of(
                new ShopeeVoucherClient.ShopeeVoucher(555L, code, "SmartOmni Gold #req-s2")));

        InternalIssueVoucherResponse res = adapterService.issueVoucher(shopeeRequest("req-s2"));

        assertThat(res.getOutcome()).isEqualTo("ISSUED");
        assertThat(res.getExternalVoucherId()).isEqualTo("555");
        verify(shopeeVoucherClient, times(1)).addPercentVoucher(anyLong(), anyString(), anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void shopeeCodeTakenBySomeoneElseMovesToTheNextCode() {
        String first = MarketplaceVoucherAdapterService.shopeeVoucherCode("req-s3", 0);
        String second = MarketplaceVoucherAdapterService.shopeeVoucherCode("req-s3", 1);
        when(shopeeVoucherClient.addPercentVoucher(eq(20L), anyString(), eq(first), any(), anyLong(), anyLong()))
                .thenThrow(new com.smartomni.integration.client.shopee.ShopeeApiException("dup", 200,
                        MarketplaceVoucherAdapterService.SHOPEE_DUPLICATE_CODE, "r", false));
        when(shopeeVoucherClient.findByCode(20L, first)).thenReturn(Optional.of(
                new ShopeeVoucherClient.ShopeeVoucher(1L, first, "Shop's own voucher")));
        when(shopeeVoucherClient.addPercentVoucher(eq(20L), anyString(), eq(second), any(), anyLong(), anyLong())).thenReturn(9L);

        InternalIssueVoucherResponse res = adapterService.issueVoucher(shopeeRequest("req-s3"));

        assertThat(res.getCode()).isEqualTo(second);
    }

    @Test
    void shopeeTransientErrorIsPendingAndRejectionFails() {
        when(shopeeVoucherClient.addPercentVoucher(anyLong(), anyString(), anyString(), any(), anyLong(), anyLong()))
                .thenThrow(com.smartomni.integration.client.shopee.ShopeeApiException.transport("timeout"));
        assertThat(adapterService.issueVoucher(shopeeRequest("req-s4")).getOutcome()).isEqualTo("PENDING");

        reset(shopeeVoucherClient);
        when(shopeeVoucherClient.addPercentVoucher(anyLong(), anyString(), anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new com.smartomni.integration.client.shopee.ShopeeApiException("bad", 200, "voucher.voucher_over_limit", "r", false));
        assertThat(adapterService.issueVoucher(shopeeRequest("req-s5")).getOutcome()).isEqualTo("FAILED");
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
