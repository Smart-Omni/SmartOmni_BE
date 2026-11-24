package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokWebhookSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
/** Retries the webhook subscriptions (all topics) of connected TikTok shops, e.g. after a failed first attempt. */
public class TiktokInventorySubscriptionScheduler {
    private final MarketplaceConnectionRepository connections;
    private final TiktokWebhookSubscriptionService subscriptions;
    private final TransactionTemplate transactions;

    @Scheduled(fixedDelayString = "${smartomni.tiktok.inventory-subscription-delay-ms:900000}",
            initialDelayString = "${smartomni.tiktok.inventory-subscription-initial-delay-ms:60000}")
    public void ensureSubscriptions() {
        if (subscriptions == null) return;
        java.util.List<MarketplaceConnection> active;
        try (var scope = TenantContext.openScope(null, "super_admin", null)) {
            active = transactions.execute(status -> connections.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED));
        }
        if (active == null) return;
        for (MarketplaceConnection connection : active) {
            if (connection.getPlatform() != MarketplaceConnection.Platform.TIKTOK_SHOP
                    || connection.getAccessTokenEncrypted() == null) continue;
            try (var scope = TenantContext.openScope(connection.getTenantId(), "order_polling_worker", null)) {
                subscriptions.ensureSubscribed(connection.getId());
            } catch (Exception e) {
                log.warn("TikTok webhook subscription retry failed: tenant={} connection={} reason={}",
                        connection.getTenantId(), connection.getId(), e.getMessage());
            }
        }
    }
}
