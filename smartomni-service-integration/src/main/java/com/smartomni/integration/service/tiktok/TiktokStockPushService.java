package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Outbox path 1 ({@code POST /internal/jobs/push-tiktok-stock}). Returns normally only when every connected
 * TikTok link accepted the stock (HTTP 2xx, envelope {@code code == 0}, SKU absent from {@code data.errors});
 * anything else propagates so the endpoint answers non-2xx and the outbox keeps the event {@code PENDING}.
 */
@Service
@RequiredArgsConstructor
public class TiktokStockPushService {
    private final TiktokStockLinkRepository links;
    private final TiktokCatalogClient catalog;

    public void push(long tenantId, long skuId, int quantity) {
        if (tenantId <= 0 || skuId <= 0 || quantity < 0) {
            throw new BusinessException("Invalid TikTok stock push request", HttpStatus.BAD_REQUEST, "TIKTOK_STOCK_INVALID");
        }
        try (var scope = TenantContext.openScope(tenantId, "tenant_admin", null)) {
            var mapped = links.findConnected(tenantId, skuId);
            if (mapped.isEmpty()) {
                throw new BusinessException("No connected TikTok mapping for SKU " + skuId,
                        HttpStatus.CONFLICT, "TIKTOK_SKU_NOT_MAPPED");
            }
            for (var link : mapped) {
                catalog.updateStock(link.connectionId(), link.productId(), link.skuId(), quantity);
            }
        }
    }
}
