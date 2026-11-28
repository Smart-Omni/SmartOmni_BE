package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderSyncResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manual TikTok order sync ({@code POST /api/v1/integrations/tiktok/orders/sync}).
 * <p>
 * The orders are not written here: each one is normalized by {@link TiktokOrderIngestionService} and published to
 * {@code order.incoming} with {@code source = POLLING}, so it goes through the same idempotent order-service path
 * (status mapping, transition table, {@code update_time} staleness check, history, wallet) as webhooks and polling.
 * Plan "marketplace-order-mapper-split" 4.4 (M12, M13).
 */
@Slf4j
@Service
public class TiktokOrderSyncService {

    static final String SOURCE_POLLING = TiktokOrderIngestionService.SOURCE_POLLING;
    private static final int MAX_SYNC_PAGES = 20;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final TiktokOrderClient orderClient;
    private final TiktokOrderIngestionService ingestionService;
    private final TiktokTrackingEnricher trackingEnricher;
    private final com.smartomni.integration.service.finance.MarketplaceFinanceSyncService financeSyncService;

    public TiktokOrderSyncService(TiktokOrderClient orderClient, TiktokOrderIngestionService ingestionService,
                                  TiktokTrackingEnricher trackingEnricher) {
        this(orderClient, ingestionService, trackingEnricher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TiktokOrderSyncService(TiktokOrderClient orderClient, TiktokOrderIngestionService ingestionService,
                                  TiktokTrackingEnricher trackingEnricher,
                                  com.smartomni.integration.service.finance.MarketplaceFinanceSyncService financeSyncService) {
        this.orderClient = orderClient;
        this.ingestionService = ingestionService;
        this.trackingEnricher = trackingEnricher;
        this.financeSyncService = financeSyncService;
    }

    public TiktokOrderSyncResponse syncOrders(MarketplaceConnection connection, Long fromDate, Long toDate) {
        if (connection == null || connection.getId() == null || connection.getTenantId() == null) {
            throw new BusinessException("Active TikTok connection is required", HttpStatus.BAD_REQUEST, "INVALID_CONNECTION");
        }

        List<TiktokOrderDetailResponse> fetchedOrders = fetchAllOrders(connection.getId(), fromDate, toDate);
        List<TiktokOrderDetailResponse> enrichedOrders = enrichOrders(connection.getId(), fetchedOrders);

        int enqueued = 0;
        int failed = 0;
        for (TiktokOrderDetailResponse order : enrichedOrders) {
            try {
                trackingEnricher.enrich(connection, order);
                ingestionService.ingestOrder(connection.getTenantId(), connection.getId(), order, SOURCE_POLLING);
                if (financeSyncService != null) {
                    financeSyncService.syncTiktokOrderFinance(connection, order.getOrderId());
                }
                enqueued++;
            } catch (Exception ex) {
                failed++;
                log.error("Could not enqueue TikTok order {} for tenant {}: {}",
                        order.getOrderId(), connection.getTenantId(), ex.getMessage());
            }
        }

        log.info("TikTok order sync enqueued: tenantId={}, connectionId={}, totalFetched={}, enqueued={}, failed={}",
                connection.getTenantId(), connection.getId(), enrichedOrders.size(), enqueued, failed);

        return TiktokOrderSyncResponse.builder()
                .totalFetched(enrichedOrders.size())
                .enqueuedCount(enqueued)
                .failedCount(failed)
                .createdCount(0)
                .updatedCount(0)
                .orders(enrichedOrders)
                .build();
    }

    private List<TiktokOrderDetailResponse> fetchAllOrders(Long connectionId, Long fromDate, Long toDate) {
        List<TiktokOrderDetailResponse> all = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        String pageToken = null;

        for (int page = 0; page < MAX_SYNC_PAGES; page++) {
            OrderSearchRequest request = OrderSearchRequest.builder()
                    .createTimeFrom(fromDate)
                    .createTimeTo(toDate)
                    .pageSize(DEFAULT_PAGE_SIZE)
                    .pageToken(pageToken)
                    .build();

            OrderSearchResponse response = orderClient.searchOrders(connectionId, request);
            if (response == null) {
                break;
            }
            if (response.getOrders() != null) {
                all.addAll(response.getOrders());
            }
            String nextToken = response.getNextPageToken();
            if (nextToken == null || nextToken.isBlank() || !seenTokens.add(nextToken)) {
                break;
            }
            pageToken = nextToken;
        }
        return all;
    }

    /** Reads the order detail only when the search result has no line items. */
    private List<TiktokOrderDetailResponse> enrichOrders(Long connectionId, List<TiktokOrderDetailResponse> orders) {
        List<TiktokOrderDetailResponse> result = new ArrayList<>(orders.size());
        for (TiktokOrderDetailResponse order : orders) {
            if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
                continue;
            }
            if (order.getLineItems() == null || order.getLineItems().isEmpty()) {
                try {
                    TiktokOrderDetailResponse detail = orderClient.getOrderDetail(connectionId, order.getOrderId());
                    if (detail != null && detail.getOrderId() != null) {
                        result.add(detail);
                        continue;
                    }
                } catch (Exception ex) {
                    log.warn("Failed to fetch TikTok order detail for orderId={}: {}", order.getOrderId(), ex.getMessage());
                }
            }
            result.add(order);
        }
        return result;
    }
}
