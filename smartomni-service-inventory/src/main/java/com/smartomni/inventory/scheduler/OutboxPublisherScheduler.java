package com.smartomni.inventory.scheduler;

import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.inventory.client.TiktokInventoryStockClient;
import com.smartomni.inventory.repository.InventoryOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import com.smartomni.common.tenant.TenantContext;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * UC-40: Outbox Publisher/Relay - doc cac ban ghi Outbox chua xu ly va goi
 * API UpdateStock tuong ung len tung san TMDT. FR-075, FR-076.
 *
 * TikTok events use absolute stock updates, so a retry after a timeout is safe.
 */
@Slf4j
@Component
public class OutboxPublisherScheduler {

    private static final int MAX_RETRY = 5;
    private static final String TIKTOK_PUSH_PATH = "/internal/jobs/push-tiktok-stock";
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    private final InventoryOutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final TiktokInventoryStockClient tiktokStockClient;
    private final RestTemplate restTemplate;

    @Value("${smartomni.services.integration-url:http://smartomni-integration-service:8086}")
    private String integrationServiceUrl;

    @Value("${smartomni.jobs.token:}")
    private String jobsToken;

    @Autowired
    public OutboxPublisherScheduler(InventoryOutboxEventRepository outboxEventRepository,
                                    TransactionTemplate transactionTemplate,
                                    TiktokInventoryStockClient tiktokStockClient,
                                    @Value("${smartomni.outbox.http.connect-timeout-ms:3000}") int connectTimeoutMs,
                                    @Value("${smartomni.outbox.http.read-timeout-ms:10000}") int readTimeoutMs) {
        this(outboxEventRepository, transactionTemplate, tiktokStockClient,
                buildRestTemplate(connectTimeoutMs, readTimeoutMs));
    }

    OutboxPublisherScheduler(InventoryOutboxEventRepository outboxEventRepository,
                             TransactionTemplate transactionTemplate,
                             TiktokInventoryStockClient tiktokStockClient) {
        this(outboxEventRepository, transactionTemplate, tiktokStockClient,
                buildRestTemplate(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS));
    }

    OutboxPublisherScheduler(InventoryOutboxEventRepository outboxEventRepository,
                             TransactionTemplate transactionTemplate,
                             TiktokInventoryStockClient tiktokStockClient,
                             RestTemplate restTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.tiktokStockClient = tiktokStockClient;
        this.restTemplate = restTemplate;
    }

    /** Bounded timeouts: one hung push must not stall the whole publish pass (it runs inside one transaction). */
    static RestTemplate buildRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Outbox HTTP timeouts must be positive");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    public void publishPendingEvents() {
        try (var scope = TenantContext.openScope(null, "outbox_worker", null)) {
            transactionTemplate.executeWithoutResult(status -> publishInTransaction());
        }
    }

    private void publishInTransaction() {
        List<InventoryOutboxEvent> pendingEvents =
                outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING);
        // A failed event blocks newer events of the same tenant+SKU in this pass to keep their order.
        Set<String> failedSkus = new HashSet<>();

        for (InventoryOutboxEvent event : pendingEvents) {
            String key = event.getTenantId() + ":" + event.getSkuId();
            if (failedSkus.contains(key)) continue;
            try {
                String targetUrl;
                if (event.getPlatform() == InventoryOutboxEvent.Platform.TIKTOK_SHOP) {
                    tiktokStockClient.push(event);
                    targetUrl = TIKTOK_PUSH_PATH;
                } else {
                    targetUrl = integrationServiceUrl + "/internal/jobs/push-stock";
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
                }

                event.setStatus(InventoryOutboxEvent.OutboxStatus.SENT);
                event.setProcessedAt(Instant.now());
                log.info("[Outbox] Successfully pushed stock event id={} (skuId={}, qty={}) to marketplace via {}",
                        event.getId(), event.getSkuId(), event.getQuantity(), targetUrl);
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
