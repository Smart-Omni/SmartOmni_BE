package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one order from TikTok Shop on demand and feeds it through the same ingestion as polling, so a loyalty claim
 * on a completed order that has not been synced yet can still be checked. The local row appears asynchronously.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokOrderRefreshService {

    private final TiktokTenantConnections connections;
    private final TiktokOrderClient orderClient;
    private final TiktokTrackingEnricher trackingEnricher;
    private final TiktokOrderIngestionService ingestionService;

    /** {@code outcome}: FOUND (with {@code orderStatus}), NOT_FOUND, NOT_CONNECTED or UNAVAILABLE. */
    public Map<String, String> refresh(Long tenantId, String platformOrderId) {
        if (tenantId == null || platformOrderId == null || platformOrderId.isBlank()) {
            return result("NOT_FOUND", null);
        }
        MarketplaceConnection connection;
        try {
            connection = connections.connected(tenantId);
        } catch (BusinessException e) {
            return result("NOT_CONNECTED", null);
        }
        TiktokOrderDetailResponse order;
        try {
            order = orderClient.getOrderDetail(connection.getId(), platformOrderId.trim());
        } catch (TiktokApiException e) {
            log.warn("TikTok order detail failed for tenant={} order={}: {}", tenantId, platformOrderId, e.getMessage());
            return result(e.getHttpStatus() >= 400 && e.getHttpStatus() < 500 && !e.isTransientFailure() ? "NOT_FOUND" : "UNAVAILABLE", null);
        } catch (RuntimeException e) {
            log.warn("TikTok order detail failed for tenant={} order={}: {}", tenantId, platformOrderId, e.getMessage());
            return result("UNAVAILABLE", null);
        }
        if (order == null || order.getOrderId() == null || !platformOrderId.trim().equals(order.getOrderId())) {
            return result("NOT_FOUND", null);
        }
        try {
            trackingEnricher.enrich(connection, order);
            ingestionService.ingestOrder(tenantId, order, TiktokOrderIngestionService.SOURCE_POLLING);
        } catch (RuntimeException e) {
            log.warn("Could not ingest refreshed TikTok order tenant={} order={}: {}", tenantId, platformOrderId, e.getMessage());
            return result("UNAVAILABLE", order.getOrderStatus());
        }
        return result("FOUND", order.getOrderStatus());
    }

    private static Map<String, String> result(String outcome, String orderStatus) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("outcome", outcome);
        map.put("orderStatus", orderStatus);
        return map;
    }
}
