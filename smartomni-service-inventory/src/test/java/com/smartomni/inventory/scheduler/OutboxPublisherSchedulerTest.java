package com.smartomni.inventory.scheduler;

import com.smartomni.inventory.entity.InventoryOutboxEvent;
import com.smartomni.inventory.repository.InventoryOutboxEventRepository;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherSchedulerTest {

    private final InventoryOutboxEventRepository repository = mock(InventoryOutboxEventRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);

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
    void marksEventSentAfterSuccessfulPush() {
        InventoryOutboxEvent event = pendingEvent();
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING)).thenReturn(List.of(event));
        runTransactionsInline();

        scheduler().publishPendingEvents();

        verify(restTemplate).postForObject(eq("http://integration:8086/internal/jobs/push-stock"), any(HttpEntity.class), eq(Map.class));
        assertEquals(InventoryOutboxEvent.OutboxStatus.SENT, event.getStatus());
        assertNotNull(event.getProcessedAt());
        verify(repository).save(event);
    }

    @Test
    void keepsEventPendingAndCountsRetryWhenPushTimesOut() {
        InventoryOutboxEvent event = pendingEvent();
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(InventoryOutboxEvent.OutboxStatus.PENDING)).thenReturn(List.of(event));
        when(restTemplate.postForObject(any(String.class), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));
        runTransactionsInline();

        scheduler().publishPendingEvents();

        assertEquals(InventoryOutboxEvent.OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNull(event.getProcessedAt());
        verify(repository).save(event);
    }

    private OutboxPublisherScheduler scheduler() {
        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(repository, transactionTemplate, restTemplate);
        ReflectionTestUtils.setField(scheduler, "integrationServiceUrl", "http://integration:8086");
        ReflectionTestUtils.setField(scheduler, "jobsToken", "test-token");
        return scheduler;
    }

    @SuppressWarnings("unchecked")
    private void runTransactionsInline() {
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private static InventoryOutboxEvent pendingEvent() {
        InventoryOutboxEvent event = new InventoryOutboxEvent();
        event.setTenantId(2L);
        event.setSkuId(10L);
        event.setPlatform(InventoryOutboxEvent.Platform.SHOPEE);
        event.setQuantity(5);
        return event;
    }
}
