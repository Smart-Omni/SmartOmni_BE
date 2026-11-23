package com.smartomni.integration.service.tiktok;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TiktokStockPushService {
    private final TiktokStockLinkRepository links;
    private final TiktokCatalogClient catalog;

    public void push(long tenantId, long skuId, int quantity) {
        if (tenantId <= 0 || skuId <= 0 || quantity < 0) {
            throw new IllegalArgumentException("Invalid TikTok stock push request");
        }
        try (var scope = TenantContext.openScope(tenantId, "tenant_admin", null)) {
            var mapped = links.findConnected(tenantId, skuId);
            if (mapped.isEmpty()) throw new IllegalStateException("No connected TikTok mapping for SKU " + skuId);
            for (var link : mapped) {
                catalog.updateStock(link.connectionId(), link.productId(), link.skuId(), quantity);
            }
        }
    }
}
