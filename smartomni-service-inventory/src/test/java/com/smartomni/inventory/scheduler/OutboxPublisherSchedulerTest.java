package com.smartomni.inventory.scheduler;

import com.smartomni.inventory.client.TiktokInventoryStockClient;
import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.inventory.repository.InventoryOutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherSchedulerTest {
    @Mock InventoryOutboxEventRepository repository;
    @Mock TransactionTemplate transactions;
    @Mock TiktokInventoryStockClient client;
    @Mock RestTemplate restTemplate;

    @Test
    void marksSentOnlyAfterPushSucceeds() {
        InventoryOutboxEvent event = event(5);
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(event));
        runTransactionCallback();

        new OutboxPublisherScheduler(repository, transactions, client).publishPendingEvents();

        verify(client).push(event);
        assertEquals(InventoryOutboxEvent.OutboxStatus.SENT, event.getStatus());
        assertNotNull(event.getProcessedAt());
        verify(repository).save(event);
    }

    @Test
    void failureKeepsPendingAndBlocksNewerValueForSameSku() {
        InventoryOutboxEvent older = event(5);
        InventoryOutboxEvent newer = event(0);
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(older, newer));
        doThrow(new IllegalStateException("TikTok unavailable")).when(client).push(older);
        runTransactionCallback();

        new OutboxPublisherScheduler(repository, transactions, client).publishPendingEvents();

        assertEquals(InventoryOutboxEvent.OutboxStatus.PENDING, older.getStatus());
        assertEquals(1, older.getRetryCount());
        assertEquals(InventoryOutboxEvent.OutboxStatus.PENDING, newer.getStatus());
        verify(client).push(older);
        verify(client, never()).push(newer);
        verify(repository).save(older);
    }

    @Test
    void nonTiktokEventDoesNotUseTiktokClient() {
        InventoryOutboxEvent event = event(3);
        event.setPlatform(InventoryOutboxEvent.Platform.SHOPEE);
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(event));
        runTransactionCallback();

        new OutboxPublisherScheduler(repository, transactions, client).publishPendingEvents();

        verify(client, never()).push(any());
        // No integration URL is configured in this unit test, so the generic push fails and is retried later.
        assertEquals(InventoryOutboxEvent.OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getRetryCount());
        verify(repository).save(event);
    }

    @Test
    void buildsRestTemplateWithBoundedTimeouts() {
        RestTemplate built = OutboxPublisherScheduler.buildRestTemplate(3000, 10000);

        SimpleClientHttpRequestFactory factory = assertInstanceOf(SimpleClientHttpRequestFactory.class, built.getRequestFactory());
        assertEquals(3000, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(10000, ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> OutboxPublisherScheduler.buildRestTemplate(0, 10000));
        assertThrows(IllegalArgumentException.class, () -> OutboxPublisherScheduler.buildRestTemplate(3000, -1));
    }

    @Test
    void marksNonTiktokEventSentAfterSuccessfulPush() {
        InventoryOutboxEvent event = event(5);
        event.setPlatform(InventoryOutboxEvent.Platform.SHOPEE);
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(event));
        runTransactionCallback();

        schedulerWithRestTemplate().publishPendingEvents();

        verify(restTemplate).postForObject(eq("http://integration:8086/internal/jobs/push-stock"), any(HttpEntity.class), eq(Map.class));
        assertEquals(InventoryOutboxEvent.OutboxStatus.SENT, event.getStatus());
        assertNotNull(event.getProcessedAt());
        verify(repository).save(event);
    }

    @Test
    void keepsNonTiktokEventPendingAndCountsRetryWhenPushTimesOut() {
        InventoryOutboxEvent event = event(5);
        event.setPlatform(InventoryOutboxEvent.Platform.SHOPEE);
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING))
                .thenReturn(List.of(event));
        when(restTemplate.postForObject(any(String.class), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));
        runTransactionCallback();

        schedulerWithRestTemplate().publishPendingEvents();

        assertEquals(InventoryOutboxEvent.OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNull(event.getProcessedAt());
        verify(repository).save(event);
    }

    private OutboxPublisherScheduler schedulerWithRestTemplate() {
        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(repository, transactions, client, restTemplate);
        ReflectionTestUtils.setField(scheduler, "integrationServiceUrl", "http://integration:8086");
        ReflectionTestUtils.setField(scheduler, "jobsToken", "test-token");
        return scheduler;
    }

    private void runTransactionCallback() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    private InventoryOutboxEvent event(int quantity) {
        InventoryOutboxEvent event = new InventoryOutboxEvent();
        event.setTenantId(1L);
        event.setSkuId(9L);
        event.setQuantity(quantity);
        event.setPlatform(InventoryOutboxEvent.Platform.TIKTOK_SHOP);
        return event;
    }
}
