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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiktokAftersalePollingSchedulerTest {

    private final MarketplaceConnectionRepository connections = mock(MarketplaceConnectionRepository.class);
    private final TiktokReturnClient returnClient = mock(TiktokReturnClient.class);
    private final TiktokCancellationClient cancellationClient = mock(TiktokCancellationClient.class);
    private final TiktokAftersaleWebhookService aftersale = mock(TiktokAftersaleWebhookService.class);
    private final TiktokSyncCheckpointStore checkpoints = mock(TiktokSyncCheckpointStore.class);
    private final TiktokAftersalePollingScheduler scheduler;
    private final MarketplaceConnection connection = new MarketplaceConnection();

    TiktokAftersalePollingSchedulerTest() {
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        scheduler = new TiktokAftersalePollingScheduler(connections, returnClient, cancellationClient, aftersale,
                checkpoints, new TransactionTemplate(ptm));
        connection.setId(10L);
        connection.setTenantId(5L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void returnsArePolledFromTheCheckpointWithMaxPageSizeAndTheCheckpointAdvances() {
        long checkpoint = Instant.now().getEpochSecond() - 900;
        when(checkpoints.find(10L, TiktokSyncCheckpointStore.RETURNS)).thenReturn(Optional.of(checkpoint));
        TiktokReturnResponse r1 = TiktokReturnResponse.builder().returnId("R1").build();
        TiktokReturnResponse r2 = TiktokReturnResponse.builder().returnId("R2").build();
        when(returnClient.searchReturns(eq(10L), argThat((ReturnSearchRequest q) -> q != null && q.getPageToken() == null)))
                .thenReturn(ReturnSearchResponse.builder().returns(List.of(r1)).nextPageToken("T2").build());
        when(returnClient.searchReturns(eq(10L), argThat((ReturnSearchRequest q) -> q != null && "T2".equals(q.getPageToken()))))
                .thenReturn(ReturnSearchResponse.builder().returns(List.of(r2)).nextPageToken("").build());

        assertEquals(2, scheduler.pollReturns(connection));

        ArgumentCaptor<ReturnSearchRequest> requests = ArgumentCaptor.forClass(ReturnSearchRequest.class);
        verify(returnClient, times(2)).searchReturns(eq(10L), requests.capture());
        ReturnSearchRequest first = requests.getAllValues().get(0);
        assertEquals(checkpoint - OrderPollingScheduler.DEFAULT_OVERLAP_SECONDS, first.getUpdateTimeFrom());
        assertEquals(TiktokAftersalePollingScheduler.RETURN_PAGE_SIZE, first.getPageSize());
        verify(aftersale).applyReturn(5L, connection, r1);
        verify(aftersale).applyReturn(5L, connection, r2);
        verify(checkpoints).advance(5L, 10L, TiktokSyncCheckpointStore.RETURNS, first.getUpdateTimeTo());
    }

    @Test
    void returnCheckpointStaysWhenOneReturnFails() {
        TiktokReturnResponse bad = TiktokReturnResponse.builder().returnId("BAD").build();
        when(returnClient.searchReturns(eq(10L), any())).thenReturn(ReturnSearchResponse.builder().returns(List.of(bad)).build());
        doThrow(new RuntimeException("order service down")).when(aftersale).applyReturn(5L, connection, bad);

        assertEquals(0, scheduler.pollReturns(connection));

        verify(checkpoints, never()).advance(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    void cancellationsArePolledByUpdateTimeAndTheCheckpointAdvances() {
        when(cancellationClient.searchCancellations(eq(10L), any())).thenReturn(CancellationSearchResponse.builder().build());

        scheduler.pollCancellations(connection);

        ArgumentCaptor<CancellationSearchRequest> request = ArgumentCaptor.forClass(CancellationSearchRequest.class);
        verify(cancellationClient).searchCancellations(eq(10L), request.capture());
        assertEquals(TiktokAftersalePollingScheduler.CANCELLATION_PAGE_SIZE, request.getValue().getPageSize());
        assertEquals(60 * 60L, request.getValue().getUpdateTimeTo() - request.getValue().getUpdateTimeFrom());
        verify(checkpoints).advance(5L, 10L, TiktokSyncCheckpointStore.CANCELLATIONS, request.getValue().getUpdateTimeTo());
    }

    @Test
    void pollAllListsConnectionsWithTheCrossTenantReadRole() {
        when(connections.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED)).thenAnswer(inv -> {
            assertEquals("price_sync_worker", TenantContext.getCurrentRole());
            return List.of(connection);
        });
        when(returnClient.searchReturns(eq(10L), any())).thenReturn(ReturnSearchResponse.builder().build());
        when(cancellationClient.searchCancellations(eq(10L), any())).thenReturn(CancellationSearchResponse.builder().build());

        scheduler.pollAll();

        verify(returnClient).searchReturns(eq(10L), any());
        verify(cancellationClient).searchCancellations(eq(10L), any());
    }
}
