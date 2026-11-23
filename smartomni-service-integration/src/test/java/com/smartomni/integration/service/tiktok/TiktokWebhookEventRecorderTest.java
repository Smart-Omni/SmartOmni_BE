package com.smartomni.integration.service.tiktok;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.WebhookEventLog;
import com.smartomni.integration.repository.WebhookEventLogRepository;
import com.smartomni.integration.service.tiktok.TiktokWebhookEventRecorder.Claim;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TiktokWebhookEventRecorderTest {

    private final WebhookEventLogRepository repository = mock(WebhookEventLogRepository.class);
    private final TiktokWebhookEventRecorder recorder = new TiktokWebhookEventRecorder(repository);

    @Test
    void firstDeliveryInsertsAnUnprocessedRowInTheTenantScope() {
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            assertEquals(5L, TenantContext.getTenantId());
            return inv.getArgument(0);
        });

        assertEquals(Claim.NEW, recorder.claim(5L, "n-1", "ORDER_STATUS_CHANGE", "{}"));

        ArgumentCaptor<WebhookEventLog> row = ArgumentCaptor.forClass(WebhookEventLog.class);
        verify(repository).saveAndFlush(row.capture());
        assertEquals("n-1", row.getValue().getEventId());
        assertEquals(MarketplaceConnection.Platform.TIKTOK_SHOP, row.getValue().getPlatform());
        assertFalse(row.getValue().isQueued());
        assertNull(row.getValue().getProcessedAt());
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void processedEventIsADuplicateAndUnprocessedOneIsARetry() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_webhook_events_log"));
        WebhookEventLog processed = new WebhookEventLog();
        processed.setProcessedAt(Instant.now());
        when(repository.findByTenantIdAndPlatformAndEventId(5L, MarketplaceConnection.Platform.TIKTOK_SHOP, "done"))
                .thenReturn(Optional.of(processed));
        when(repository.findByTenantIdAndPlatformAndEventId(5L, MarketplaceConnection.Platform.TIKTOK_SHOP, "failed"))
                .thenReturn(Optional.of(new WebhookEventLog()));

        assertEquals(Claim.DUPLICATE, recorder.claim(5L, "done", "ORDER_STATUS_CHANGE", "{}"));
        assertEquals(Claim.RETRY, recorder.claim(5L, "failed", "ORDER_STATUS_CHANGE", "{}"));
    }

    @Test
    void markProcessedSetsProcessedAtAndQueued() {
        WebhookEventLog row = new WebhookEventLog();
        when(repository.findByTenantIdAndPlatformAndEventId(5L, MarketplaceConnection.Platform.TIKTOK_SHOP, "n-1"))
                .thenReturn(Optional.of(row));

        recorder.markProcessed(5L, "n-1", "ORDER_STATUS_CHANGE");

        assertNotNull(row.getProcessedAt());
        assertTrue(row.isQueued());
        verify(repository).save(row);
    }

    @Test
    void rejectedDeliveryDoesNotReserveTheEventId() {
        recorder.recordRejected(5L, "ORDER_STATUS_CHANGE", "{\"tts_notification_id\":\"n-1\"}");

        ArgumentCaptor<WebhookEventLog> row = ArgumentCaptor.forClass(WebhookEventLog.class);
        verify(repository).save(row.capture());
        assertNull(row.getValue().getEventId());
        assertFalse(row.getValue().getSignatureValid());
    }
}
