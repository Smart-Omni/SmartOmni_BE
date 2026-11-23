package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokCancellationClient;
import com.smartomni.integration.client.tiktok.TiktokReturnClient;
import com.smartomni.integration.dto.tiktok.CancellationSearchRequest;
import com.smartomni.integration.dto.tiktok.CancellationSearchResponse;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokAftersaleWebhookService;
import com.smartomni.integration.service.tiktok.TiktokSyncCheckpointStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fallback polling for TikTok returns and cancellations when webhooks 11/12 are lost. Same rules as
 * {@link OrderPollingScheduler}: window {@code update_time_ge = checkpoint - overlap} to {@code update_time_lt = now},
 * every page, and the checkpoint ({@code RETURNS} / {@code CANCELLATIONS} in {@code tiktok_sync_checkpoints})
 * advances only when the whole window was applied. Records are applied through
 * {@link TiktokAftersaleWebhookService}, so polling and webhooks share one code path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokAftersalePollingScheduler {

    static final int RETURN_PAGE_SIZE = 50;       // TikTok accepts 10-50
    static final int CANCELLATION_PAGE_SIZE = 50; // TikTok accepts 1-50
    static final int MAX_PAGES = 200;
    private static final String WORKER_ROLE = "order_polling_worker";

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokReturnClient returnClient;
    private final TiktokCancellationClient cancellationClient;
    private final TiktokAftersaleWebhookService aftersale;
    private final TiktokSyncCheckpointStore checkpoints;
    private final TransactionTemplate transactionTemplate;

    @Value("${smartomni.aftersale-polling.lookback-minutes:60}")
    private long lookbackMinutes = 60;

    @Value("${smartomni.order-polling.overlap-seconds:300}")
    private long overlapSeconds = OrderPollingScheduler.DEFAULT_OVERLAP_SECONDS;

    @Scheduled(fixedDelayString = "${smartomni.aftersale-polling.fixed-delay-ms:900000}",
            initialDelayString = "${smartomni.aftersale-polling.initial-delay-ms:120000}")
    public void pollScheduled() {
        pollAll();
    }

    public void pollAll() {
        List<MarketplaceConnection> connections;
        // Cross-tenant read on marketplace_connections is allowed to price_sync_worker only (V6).
        try (var scope = TenantContext.openScope(null, "price_sync_worker", null)) {
            connections = transactionTemplate.execute(status ->
                    connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED));
        }
        if (connections == null) {
            return;
        }
        for (MarketplaceConnection connection : connections) {
            if (connection.getPlatform() != MarketplaceConnection.Platform.TIKTOK_SHOP || connection.getTenantId() == null) {
                continue;
            }
            try {
                pollReturns(connection);
            } catch (Exception ex) {
                log.error("TikTok return polling failed: tenant={} connection={}: {}",
                        connection.getTenantId(), connection.getId(), ex.getMessage());
            }
            try {
                pollCancellations(connection);
            } catch (Exception ex) {
                log.error("TikTok cancellation polling failed: tenant={} connection={}: {}",
                        connection.getTenantId(), connection.getId(), ex.getMessage());
            }
        }
    }

    /** @return number of returns applied */
    public int pollReturns(MarketplaceConnection connection) {
        try (var scope = TenantContext.openScope(connection.getTenantId(), WORKER_ROLE, null)) {
            long to = Instant.now().getEpochSecond();
            long from = windowStart(connection, TiktokSyncCheckpointStore.RETURNS, to);
            if (from >= to) return 0;
            int applied = 0;
            boolean complete = true;
            String token = null;
            Set<String> seen = new HashSet<>();
            for (int page = 0; page < MAX_PAGES; page++) {
                ReturnSearchResponse response = returnClient.searchReturns(connection.getId(), ReturnSearchRequest.builder()
                        .pageSize(RETURN_PAGE_SIZE).pageToken(token).updateTimeFrom(from).updateTimeTo(to).build());
                if (response != null && response.getReturns() != null) {
                    for (TiktokReturnResponse ret : response.getReturns()) {
                        try {
                            aftersale.applyReturn(connection.getTenantId(), connection, ret);
                            applied++;
                        } catch (Exception ex) {
                            complete = false;
                            log.error("Could not apply TikTok return {}: {}", ret.getReturnId(), ex.getMessage());
                        }
                    }
                }
                token = response == null ? null : blankToNull(response.getNextPageToken());
                if (token == null || !seen.add(token)) break;
            }
            if (complete) {
                checkpoints.advance(connection.getTenantId(), connection.getId(), TiktokSyncCheckpointStore.RETURNS, to);
            }
            return applied;
        }
    }

    /** @return number of cancellations seen */
    public int pollCancellations(MarketplaceConnection connection) {
        try (var scope = TenantContext.openScope(connection.getTenantId(), WORKER_ROLE, null)) {
            long to = Instant.now().getEpochSecond();
            long from = windowStart(connection, TiktokSyncCheckpointStore.CANCELLATIONS, to);
            if (from >= to) return 0;
            int seenCount = 0;
            String token = null;
            Set<String> seen = new HashSet<>();
            for (int page = 0; page < MAX_PAGES; page++) {
                CancellationSearchResponse response = cancellationClient.searchCancellations(connection.getId(),
                        CancellationSearchRequest.builder().pageSize(CANCELLATION_PAGE_SIZE).pageToken(token)
                                .updateTimeFrom(from).updateTimeTo(to).build());
                if (response != null && response.getCancellations() != null) {
                    for (var cancellation : response.getCancellations()) {
                        // Order status follows ORDER_STATUS_CHANGE / order polling; this keeps the audit trail.
                        log.info("TikTok cancellation {} of order {} (tenant {}): status={}", cancellation.getCancelId(),
                                cancellation.getOrderId(), connection.getTenantId(), cancellation.getCancelStatus());
                        seenCount++;
                    }
                }
                token = response == null ? null : blankToNull(response.getNextPageToken());
                if (token == null || !seen.add(token)) break;
            }
            checkpoints.advance(connection.getTenantId(), connection.getId(), TiktokSyncCheckpointStore.CANCELLATIONS, to);
            return seenCount;
        }
    }

    private long windowStart(MarketplaceConnection connection, String resource, long to) {
        return checkpoints.find(connection.getId(), resource)
                .map(checkpoint -> Math.max(0, checkpoint - Math.max(0, overlapSeconds)))
                .orElse(to - lookbackMinutes * 60L);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
