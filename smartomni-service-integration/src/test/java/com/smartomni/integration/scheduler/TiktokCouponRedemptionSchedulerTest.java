package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokVoucherClient;
import com.smartomni.integration.dto.tiktok.TiktokVoucherDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokVoucherStore;
import com.smartomni.integration.service.tiktok.TiktokVoucherStore.IssuedCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TiktokCouponRedemptionSchedulerTest {

    private final MarketplaceConnectionRepository connections = mock(MarketplaceConnectionRepository.class);
    private final TiktokVoucherClient client = mock(TiktokVoucherClient.class);
    private final TiktokVoucherStore store = mock(TiktokVoucherStore.class);
    private final TiktokCouponRedemptionScheduler scheduler;

    TiktokCouponRedemptionSchedulerTest() {
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        scheduler = new TiktokCouponRedemptionScheduler(connections, client, store, new TransactionTemplate(ptm));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static TiktokVoucherDto coupon(String status, int redeemed) {
        return TiktokVoucherDto.builder().status(status).redeemedCount(redeemed).build();
    }

    @Test
    void storesStatusAndRedeemedCountOfIssuedOpenCodesOnly() {
        when(store.issuedOpen(5L)).thenReturn(List.of(new IssuedCode(1, 10, "C1"), new IssuedCode(2, 10, "C2"),
                new IssuedCode(3, 10, "C3")));
        when(client.getCoupon(10L, "C1")).thenReturn(coupon("ONGOING", 1));
        when(client.getCoupon(10L, "C2")).thenReturn(coupon("DEACTIVATED", 0));
        when(client.getCoupon(10L, "C3")).thenReturn(coupon("ONGOING", 0));

        assertEquals(3, scheduler.trackTenant(5L));

        verify(store).updateUsage(5L, 1, "ONGOING", 1);
        verify(store).updateUsage(5L, 2, "DEACTIVATED", 0);
        verify(store).updateUsage(5L, 3, "ONGOING", 0);
    }

    @Test
    void finalCodesAreNotPolledAgain() {
        // issuedOpen only returns ONGOING codes with redeemed_count = 0; once used or burned they drop out.
        when(store.issuedOpen(5L)).thenReturn(List.of());

        assertEquals(0, scheduler.trackTenant(5L));

        verifyNoInteractions(client);
    }

    @Test
    void oneFailingCodeDoesNotStopTheOthers() {
        when(store.issuedOpen(5L)).thenReturn(List.of(new IssuedCode(1, 10, "C1"), new IssuedCode(2, 10, "C2")));
        when(client.getCoupon(10L, "C1")).thenThrow(new RuntimeException("timeout"));
        when(client.getCoupon(10L, "C2")).thenReturn(coupon("EXPIRED", 0));

        assertEquals(1, scheduler.trackTenant(5L));

        verify(store).updateUsage(5L, 2, "EXPIRED", 0);
    }

    @Test
    void tenantsComeFromConnectedTiktokShopsReadWithTheCrossTenantRole() {
        MarketplaceConnection c = new MarketplaceConnection();
        c.setTenantId(5L);
        c.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        when(connections.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED)).thenAnswer(inv -> {
            assertEquals("price_sync_worker", TenantContext.getCurrentRole());
            return List.of(c);
        });
        when(store.issuedOpen(5L)).thenReturn(List.of());

        scheduler.trackAll();

        verify(store).issuedOpen(5L);
    }
}
