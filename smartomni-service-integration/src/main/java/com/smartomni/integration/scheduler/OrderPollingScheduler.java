package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
import com.smartomni.integration.service.tiktok.TiktokSyncCheckpointStore;
import com.smartomni.integration.service.tiktok.TiktokTrackingEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Scheduler quét và đồng bộ đơn hàng định kỳ từ TikTok Shop (Fallback Polling Sync - UC-34).
 * - Quét các kết nối CONNECTED của sàn TikTok Shop.
 * - Gọi TiktokOrderClient searchOrders theo cửa sổ update_time từ checkpoint (tiktok_sync_checkpoints) tới hiện tại.
 * - Hỗ trợ phân trang qua next_page_token.
 * - Chuyển từng đơn hàng cho TiktokOrderIngestionService xử lý map SKU và đẩy vào RabbitMQ.
 * - Cô lập lỗi từng tenant (try/catch riêng) để sự cố của 1 tenant không làm ngắt các tenant khác.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPollingScheduler {

    public static final int DEFAULT_LOOKBACK_MINUTES = 30;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final long DEFAULT_OVERLAP_SECONDS = 300;

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokOrderClient tiktokOrderClient;
    private final TiktokOrderIngestionService ingestionService;
    private final TransactionTemplate transactionTemplate;
    private final TiktokSyncCheckpointStore checkpoints;
    private final TiktokTrackingEnricher trackingEnricher;

    /**
     * Seconds re-read before the checkpoint on every run. update_time comes from the platform clock and a platform
     * transaction may commit after our search, so a small overlap keeps such orders from falling behind the
     * checkpoint. Re-ingesting is safe: ingest is idempotent and the order service ignores stale updates.
     */
    @org.springframework.beans.factory.annotation.Value("${smartomni.order-polling.overlap-seconds:300}")
    private long overlapSeconds = DEFAULT_OVERLAP_SECONDS;

    @Scheduled(fixedDelayString = "${smartomni.order-polling.fixed-delay-ms:900000}", initialDelayString = "${smartomni.order-polling.initial-delay-ms:60000}")
    public void pollOrdersScheduled() {
        pollOrdersFromMarketplaces();
    }

    /**
     * Quét đơn hàng định kỳ với khoảng thời gian mặc định 30 phút.
     */
    public int pollOrdersFromMarketplaces() {
        return pollOrdersFromMarketplaces(DEFAULT_LOOKBACK_MINUTES);
    }

    /**
     * Quét đơn hàng cho toàn bộ tenant có kết nối TikTok CONNECTED với khoảng lookback tùy chỉnh.
     */
    public int pollOrdersFromMarketplaces(int lookbackMinutes) {
        log.info("Bắt đầu chu kỳ Polling đồng bộ đơn hàng TikTok (lookback: {} phút)", lookbackMinutes);

        List<MarketplaceConnection> connections;
        // Listing across tenants needs a role with a read policy on marketplace_connections. V6 grants that to
        // price_sync_worker only; with "order_polling_worker" forced RLS returned no rows and polling never ran.
        try (var scope = TenantContext.openScope(null, "price_sync_worker", null)) {
            connections = (transactionTemplate != null)
                    ? transactionTemplate.execute(status -> connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED))
                    : connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        }

        if (connections == null || connections.isEmpty()) {
            log.info("Không có kết nối sàn nào đang ở trạng thái CONNECTED");
            return 0;
        }

        int totalSyncedOrders = 0;
        for (MarketplaceConnection connection : connections) {
            if (connection.getPlatform() != MarketplaceConnection.Platform.TIKTOK_SHOP) {
                continue;
            }

            try {
                int count = pollOrdersForConnection(connection, lookbackMinutes);
                totalSyncedOrders += count;
            } catch (Exception ex) {
                log.error("Lỗi khi polling đơn hàng cho tenant={}, connectionId={}, shopCipher={}: {}",
                        connection.getTenantId(), connection.getId(), connection.getShopCipher(), ex.getMessage(), ex);
            }
        }

        log.info("Kết thúc chu kỳ Polling đồng bộ đơn hàng. Tổng số đơn hàng đã ingest: {}", totalSyncedOrders);
        return totalSyncedOrders;
    }

    /**
     * Quét và ingest đơn hàng cho một kết nối TikTok theo cửa sổ {@code update_time} có checkpoint, đi hết phân trang.
     * <ul>
     *   <li>Cửa sổ: {@code update_time_ge = checkpoint - overlap} (gồm) đến {@code update_time_lt = now} (không gồm).
     *       Lần chạy đầu tiên (chưa có checkpoint) dùng {@code now - lookback}.</li>
     *   <li>Checkpoint chỉ tiến lên {@code now} khi đã ingest xong cả cửa sổ. Lỗi giữa chừng (một đơn hoặc một trang)
     *       giữ nguyên checkpoint, nên lần sau quét lại cửa sổ đó; ingest là idempotent theo đơn.</li>
     * </ul>
     */
    public int pollOrdersForConnection(MarketplaceConnection connection, int lookbackMinutes) {
        if (connection == null || connection.getTenantId() == null) {
            return 0;
        }

        log.info("Bắt đầu quét đơn hàng cho tenant={}, connectionId={}, shopCipher={}",
                connection.getTenantId(), connection.getId(), connection.getShopCipher());

        int connectionSyncedCount = 0;
        int pageNumber = 1;
        String pageToken = null;
        boolean windowComplete = true;

        try (var scope = TenantContext.openScope(connection.getTenantId(), "order_polling_worker", null)) {
            long updateTimeTo = Instant.now().getEpochSecond();
            long updateTimeFrom = checkpoints.find(connection.getId(), TiktokSyncCheckpointStore.ORDERS)
                    .map(checkpoint -> Math.max(0, checkpoint - Math.max(0, overlapSeconds)))
                    .orElse(updateTimeTo - (lookbackMinutes * 60L));
            if (updateTimeFrom >= updateTimeTo) {
                return 0;
            }
            do {
                OrderSearchRequest request = OrderSearchRequest.builder()
                        .pageSize(DEFAULT_PAGE_SIZE)
                        .pageToken(pageToken)
                        .updateTimeFrom(updateTimeFrom)
                        .updateTimeTo(updateTimeTo)
                        .build();

                OrderSearchResponse response;
                if (connection.getShopCipher() != null && !connection.getShopCipher().isBlank()) {
                    response = tiktokOrderClient.searchOrders(connection.getShopCipher(), request);
                } else {
                    response = tiktokOrderClient.searchOrders(connection.getId(), request);
                }

                if (response != null && response.getOrders() != null && !response.getOrders().isEmpty()) {
                    log.info("Quét được {} đơn hàng (page {}) cho tenant={}, connectionId={}",
                            response.getOrders().size(), pageNumber, connection.getTenantId(), connection.getId());

                    for (TiktokOrderDetailResponse order : response.getOrders()) {
                        try {
                            trackingEnricher.enrich(connection, order);
                            ingestionService.ingestOrder(connection.getTenantId(), order,
                                    TiktokOrderIngestionService.SOURCE_POLLING);
                            connectionSyncedCount++;
                        } catch (Exception ex) {
                            windowComplete = false;
                            log.error("Lỗi khi ingest đơn hàng id={} cho tenant={}: {}",
                                    order.getOrderId(), connection.getTenantId(), ex.getMessage(), ex);
                        }
                    }
                }

                pageToken = (response != null && response.getNextPageToken() != null && !response.getNextPageToken().isBlank())
                        ? response.getNextPageToken().trim()
                        : null;
                pageNumber++;

            } while (pageToken != null);

            if (windowComplete) {
                checkpoints.advance(connection.getTenantId(), connection.getId(), TiktokSyncCheckpointStore.ORDERS, updateTimeTo);
            } else {
                log.warn("Giữ nguyên checkpoint đơn hàng cho connectionId={} vì có đơn ingest lỗi", connection.getId());
            }
        }

        log.info("Hoàn tất polling đơn hàng cho tenant={}, connectionId={}: tổng cộng {} đơn",
                connection.getTenantId(), connection.getId(), connectionSyncedCount);
        return connectionSyncedCount;
    }
}
