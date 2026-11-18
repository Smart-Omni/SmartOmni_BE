package com.smartomni.inventory.scheduler;

import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.inventory.client.TiktokInventoryStockClient;
import com.smartomni.inventory.repository.InventoryOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.smartomni.common.tenant.TenantContext;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * UC-40: Outbox Publisher/Relay - doc cac ban ghi Outbox chua xu ly va goi
 * API UpdateStock tuong ung len tung san TMDT. FR-075, FR-076.
 *
 * TikTok events use absolute stock updates, so a retry after a timeout is safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private static final int MAX_RETRY = 5;

    private final InventoryOutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final TiktokInventoryStockClient tiktokStockClient;

    public void publishPendingEvents() {
        try (var scope = TenantContext.openScope(null, "outbox_worker", null)) {
            transactionTemplate.executeWithoutResult(status -> publishInTransaction());
        }
    }

    private void publishInTransaction() {
        List<InventoryOutboxEvent> pendingEvents =
                outboxEventRepository.findTop100ByStatusAndPlatformOrderByCreatedAtAsc(
                        InventoryOutboxEvent.OutboxStatus.PENDING, InventoryOutboxEvent.Platform.TIKTOK_SHOP);
        Set<String> failedSkus = new HashSet<>();

        for (InventoryOutboxEvent event : pendingEvents) {
            String key = event.getTenantId() + ":" + event.getSkuId();
            if (failedSkus.contains(key)) continue;
            try {
                tiktokStockClient.push(event);
                event.setStatus(InventoryOutboxEvent.OutboxStatus.SENT);
                event.setProcessedAt(Instant.now());
            } catch (Exception ex) {
                failedSkus.add(key);
                log.warn("Outbox publish that bai cho event id={}: {}", event.getId(), ex.getMessage());
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= MAX_RETRY) {
                    event.setStatus(InventoryOutboxEvent.OutboxStatus.FAILED); // FR-076: loi vinh vien, can Manager kiem tra
                }
            }
            outboxEventRepository.save(event);
        }
    }
}
