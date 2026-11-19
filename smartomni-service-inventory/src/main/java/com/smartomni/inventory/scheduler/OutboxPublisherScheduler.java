package com.smartomni.inventory.scheduler;

import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.inventory.repository.InventoryOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import com.smartomni.common.tenant.TenantContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * UC-40: Outbox Publisher/Relay - doc cac ban ghi Outbox chua xu ly va goi
 * API UpdateStock tuong ung len tung san TMDT. FR-075, FR-076.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private static final int MAX_RETRY = 5;

    private final InventoryOutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${smartomni.services.integration-url:http://smartomni-integration-service:8086}")
    private String integrationServiceUrl;

    @Value("${smartomni.jobs.token:}")
    private String jobsToken;

    public void publishPendingEvents() {
        try (var scope = TenantContext.openScope(null, "outbox_worker", null)) {
            transactionTemplate.executeWithoutResult(status -> publishInTransaction());
        }
    }

    private void publishInTransaction() {
        List<InventoryOutboxEvent> pendingEvents =
                outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING);

        for (InventoryOutboxEvent event : pendingEvents) {
            try {
                String targetUrl = integrationServiceUrl + "/internal/jobs/push-stock";
                Map<String, Object> req = Map.of(
                        "tenantId", event.getTenantId(),
                        "platform", event.getPlatform().name(),
                        "skuId", event.getSkuId(),
                        "quantity", event.getQuantity()
                );
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                String tokenToSend = (jobsToken != null && !jobsToken.isBlank()) ? jobsToken : System.getenv("SMARTOMNI_JOBS_TOKEN");
                if (tokenToSend != null && !tokenToSend.isBlank()) {
                    headers.set("X-Internal-Job-Token", tokenToSend);
                }
                org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(req, headers);
                restTemplate.postForObject(targetUrl, entity, Map.class);

                event.setStatus(InventoryOutboxEvent.OutboxStatus.SENT);
                event.setProcessedAt(Instant.now());
                log.info("[Outbox] Successfully pushed stock event id={} (skuId={}, qty={}) to marketplace via {}",
                        event.getId(), event.getSkuId(), event.getQuantity(), targetUrl);
            } catch (Exception ex) {
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
