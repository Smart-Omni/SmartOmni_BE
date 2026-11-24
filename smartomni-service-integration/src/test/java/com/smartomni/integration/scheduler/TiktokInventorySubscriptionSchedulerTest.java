package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokWebhookSubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TiktokInventorySubscriptionSchedulerTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void retriesEachConnectedTikTokShopWithItsOwnTenant() {
        var connections = mock(MarketplaceConnectionRepository.class);
        var subscriptions = mock(TiktokWebhookSubscriptionService.class);
        var transactions = mock(TransactionTemplate.class);
        var first = connection(1L, 11L, MarketplaceConnection.Platform.TIKTOK_SHOP);
        var second = connection(2L, 22L, MarketplaceConnection.Platform.TIKTOK_SHOP);
        var shopee = connection(3L, 33L, MarketplaceConnection.Platform.SHOPEE);
        when(transactions.execute(any())).thenAnswer(inv -> {
            assertEquals("super_admin", TenantContext.getCurrentRole());
            var callback = (org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(connections.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED))
                .thenReturn(List.of(first, shopee, second));
        doAnswer(inv -> {
            assertEquals(inv.getArgument(0, Long.class) == 11L ? 1L : 2L, TenantContext.getTenantId());
            if (inv.getArgument(0, Long.class) == 11L) throw new IllegalStateException("temporary failure");
            return true;
        }).when(subscriptions).ensureSubscribed(any(Long.class));

        new TiktokInventorySubscriptionScheduler(connections, subscriptions, transactions).ensureSubscriptions();

        verify(subscriptions).ensureSubscribed(11L);
        verify(subscriptions).ensureSubscribed(22L);
        verifyNoMoreInteractions(subscriptions);
    }

    private static MarketplaceConnection connection(long tenantId, long id, MarketplaceConnection.Platform platform) {
        var result = new MarketplaceConnection();
        result.setTenantId(tenantId);
        result.setId(id);
        result.setPlatform(platform);
        result.setAccessTokenEncrypted("encrypted");
        return result;
    }
}
