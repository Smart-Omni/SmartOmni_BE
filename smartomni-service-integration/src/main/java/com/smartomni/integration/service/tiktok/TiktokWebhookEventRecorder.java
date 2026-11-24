package com.smartomni.integration.service.tiktok;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.WebhookEventLog;
import com.smartomni.integration.repository.WebhookEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Idempotency for TikTok webhooks on {@code uq_webhook_events_log (tenant_id, platform, event_id)}, where
 * {@code event_id} is {@code tts_notification_id}.
 *
 * <p>The log row is inserted (own transaction) before the event is handled and marked processed afterwards. A
 * second delivery of a processed event is a {@link Claim#DUPLICATE}. A delivery whose earlier attempt failed
 * (row still unprocessed) is a {@link Claim#RETRY} and is handled again, so a 503 answered to the platform never
 * loses the event; the handlers downstream are idempotent on the order id.
 *
 * <p>Rejected deliveries (bad signature) are logged with a null {@code event_id}: PostgreSQL allows many NULLs in the
 * unique constraint, and a forged request must not be able to reserve the id of a real event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokWebhookEventRecorder {

    public enum Claim { NEW, RETRY, DUPLICATE }

    private final WebhookEventLogRepository repository;

    public Claim claim(Long tenantId, String eventId, String eventType, String rawPayload) {
        try (var scope = TenantContext.openScope(tenantId, null, null)) {
            try {
                repository.saveAndFlush(newLog(tenantId, eventId, eventType, rawPayload, true));
                return Claim.NEW;
            } catch (DataIntegrityViolationException duplicate) {
                return repository.findByTenantIdAndPlatformAndEventId(tenantId, MarketplaceConnection.Platform.TIKTOK_SHOP, eventId)
                        .map(existing -> existing.getProcessedAt() != null ? Claim.DUPLICATE : Claim.RETRY)
                        // The conflicting row is not visible (should not happen under the same tenant): be safe.
                        .orElse(Claim.DUPLICATE);
            }
        }
    }

    /** Marks the event handled; later deliveries of the same id are duplicates. */
    public void markProcessed(Long tenantId, String eventId, String eventType) {
        try (var scope = TenantContext.openScope(tenantId, null, null)) {
            repository.findByTenantIdAndPlatformAndEventId(tenantId, MarketplaceConnection.Platform.TIKTOK_SHOP, eventId)
                    .ifPresent(row -> {
                        Instant now = Instant.now();
                        row.setEventType(eventType);
                        row.setSignatureValid(true);
                        row.setQueued(true);
                        row.setProcessedAt(now);
                        repository.save(row);
                    });
        } catch (Exception e) {
            // The event was handled; failing here only means a later duplicate is handled again (idempotent).
            log.warn("Could not mark TikTok webhook {} processed: {}", eventId, e.getMessage());
        }
    }

    /** Audit row for a delivery that failed signature verification. Never throws. */
    public void recordRejected(Long tenantId, String eventType, String rawPayload) {
        try (var scope = TenantContext.openScope(tenantId, null, null)) {
            repository.save(newLog(tenantId, null, eventType, rawPayload, false));
        } catch (Exception e) {
            log.warn("Could not store rejected TikTok webhook audit row: {}", e.getMessage());
        }
    }

    private static WebhookEventLog newLog(Long tenantId, String eventId, String eventType, String rawPayload,
                                          boolean signatureValid) {
        WebhookEventLog row = new WebhookEventLog();
        row.setTenantId(tenantId);
        row.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setSignatureValid(signatureValid);
        row.setRawPayload(rawPayload);
        row.setQueued(false);
        row.setReceivedAt(Instant.now());
        return row;
    }
}
