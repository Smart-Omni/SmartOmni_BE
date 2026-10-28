package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
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
 * - Gọi TiktokOrderClient searchOrders để lấy danh sách đơn hàng phát sinh trong khoảng thời gian vừa qua.
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

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokOrderClient tiktokOrderClient;
    private final TiktokOrderIngestionService ingestionService;
    private final TransactionTemplate transactionTemplate;

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
        try (var scope = TenantContext.openScope(null, "order_polling_worker", null)) {
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
     * Quét và ingest đơn hàng cho một kết nối TikTok cụ thể, xử lý đầy đủ phân trang.
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

        long createTimeTo = Instant.now().getEpochSecond();
        long createTimeFrom = createTimeTo - (lookbackMinutes * 60L);

        try (var scope = TenantContext.openScope(connection.getTenantId(), "order_polling_worker", null)) {
            do {
                OrderSearchRequest request = OrderSearchRequest.builder()
                        .pageSize(DEFAULT_PAGE_SIZE)
                        .pageToken(pageToken)
                        .createTimeFrom(createTimeFrom)
                        .createTimeTo(createTimeTo)
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
                            ingestionService.ingestOrder(connection.getTenantId(), order);
                            connectionSyncedCount++;
                        } catch (Exception ex) {
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
        }

        log.info("Hoàn tất polling đơn hàng cho tenant={}, connectionId={}: tổng cộng {} đơn",
                connection.getTenantId(), connection.getId(), connectionSyncedCount);
        return connectionSyncedCount;
    }
}
