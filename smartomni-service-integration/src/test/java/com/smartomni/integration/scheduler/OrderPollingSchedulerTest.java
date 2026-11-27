package com.smartomni.integration.scheduler;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
import com.smartomni.integration.service.tiktok.TiktokSyncCheckpointStore;
import com.smartomni.integration.service.tiktok.TiktokTrackingEnricher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderPollingScheduler Unit Tests")
class OrderPollingSchedulerTest {

    private static final String POLLING = TiktokOrderIngestionService.SOURCE_POLLING;

    @Mock
    private MarketplaceConnectionRepository connectionRepository;

    @Mock
    private TiktokOrderClient tiktokOrderClient;

    @Mock
    private TiktokOrderIngestionService ingestionService;

    @Mock
    private TiktokSyncCheckpointStore checkpoints;

    @Mock
    private TiktokTrackingEnricher trackingEnricher;

    private TransactionTemplate transactionTemplate;
    private OrderPollingScheduler scheduler;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        lenient().when(ptm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        transactionTemplate = new TransactionTemplate(ptm);

        scheduler = new OrderPollingScheduler(
                connectionRepository,
                tiktokOrderClient,
                ingestionService,
                transactionTemplate,
                checkpoints,
                trackingEnricher
        );
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MarketplaceConnection createConnection(Long id, Long tenantId, MarketplaceConnection.Platform platform, String shopCipher) {
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setId(id);
        connection.setTenantId(tenantId);
        connection.setPlatform(platform);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        connection.setShopCipher(shopCipher);
        return connection;
    }

    @Test
    @DisplayName("Scheduler tự động lấy kết nối active, gọi OrderClient search và chuyển cho IngestionService xử lý (single page)")
    void pollOrdersFromMarketplaces_success_singlePage() {
        MarketplaceConnection conn = createConnection(1L, 100L, MarketplaceConnection.Platform.TIKTOK_SHOP, "SHOP-CIPHER-100");
        when(connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED))
                .thenReturn(List.of(conn));

        TiktokOrderDetailResponse order1 = TiktokOrderDetailResponse.builder().orderId("TT-ORD-1").build();
        TiktokOrderDetailResponse order2 = TiktokOrderDetailResponse.builder().orderId("TT-ORD-2").build();
        OrderSearchResponse searchResponse = OrderSearchResponse.builder()
                .orders(List.of(order1, order2))
                .nextPageToken(null)
                .totalCount(2)
                .build();

        when(tiktokOrderClient.searchOrders(eq("SHOP-CIPHER-100"), any(OrderSearchRequest.class)))
                .thenReturn(searchResponse);

        int synced = scheduler.pollOrdersFromMarketplaces(30);

        assertEquals(2, synced);
        verify(ingestionService).ingestOrder(100L, order1, POLLING);
        verify(ingestionService).ingestOrder(100L, order2, POLLING);
        verify(trackingEnricher).enrich(conn, order1);
        verify(trackingEnricher).enrich(conn, order2);
        verify(tiktokOrderClient, times(1)).searchOrders(eq("SHOP-CIPHER-100"), any(OrderSearchRequest.class));
    }

    @Test
    @DisplayName("Xử lý phân trang nhiều trang (multi-page) qua next_page_token")
    void pollOrdersFromMarketplaces_success_multiPage() {
        MarketplaceConnection conn = createConnection(2L, 200L, MarketplaceConnection.Platform.TIKTOK_SHOP, "SHOP-CIPHER-200");
        when(connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED))
                .thenReturn(List.of(conn));

        TiktokOrderDetailResponse page1Order = TiktokOrderDetailResponse.builder().orderId("PAGE1-ORD-1").build();
        OrderSearchResponse page1Response = OrderSearchResponse.builder()
                .orders(List.of(page1Order))
                .nextPageToken("PAGE_2_TOKEN")
                .build();

        TiktokOrderDetailResponse page2Order = TiktokOrderDetailResponse.builder().orderId("PAGE2-ORD-2").build();
        OrderSearchResponse page2Response = OrderSearchResponse.builder()
                .orders(List.of(page2Order))
                .nextPageToken(null)
                .build();

        // Trang 1: token null
        when(tiktokOrderClient.searchOrders(eq("SHOP-CIPHER-200"), argThat(req -> req.getPageToken() == null)))
                .thenReturn(page1Response);

        // Trang 2: token PAGE_2_TOKEN
        when(tiktokOrderClient.searchOrders(eq("SHOP-CIPHER-200"), argThat(req -> "PAGE_2_TOKEN".equals(req.getPageToken()))))
                .thenReturn(page2Response);

        int synced = scheduler.pollOrdersFromMarketplaces(15);

        assertEquals(2, synced);
        verify(ingestionService).ingestOrder(200L, page1Order, POLLING);
        verify(ingestionService).ingestOrder(200L, page2Order, POLLING);

        ArgumentCaptor<OrderSearchRequest> reqCaptor = ArgumentCaptor.forClass(OrderSearchRequest.class);
        verify(tiktokOrderClient, times(2)).searchOrders(eq("SHOP-CIPHER-200"), reqCaptor.capture());
        assertNull(reqCaptor.getAllValues().get(0).getPageToken());
        assertEquals("PAGE_2_TOKEN", reqCaptor.getAllValues().get(1).getPageToken());
    }

    @Test
    @DisplayName("Error handling cô lập lỗi: 1 tenant gặp sự cố API không làm gián đoạn tenant khác")
    void pollOrdersFromMarketplaces_isolatedError_whenOneTenantFails() {
        MarketplaceConnection failedConn = createConnection(10L, 501L, MarketplaceConnection.Platform.TIKTOK_SHOP, "CIPHER-FAIL");
        MarketplaceConnection successConn = createConnection(11L, 502L, MarketplaceConnection.Platform.TIKTOK_SHOP, "CIPHER-SUCCESS");

        when(connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED))
                .thenReturn(List.of(failedConn, successConn));

        // Tenant 1 ném exception
        when(tiktokOrderClient.searchOrders(eq("CIPHER-FAIL"), any(OrderSearchRequest.class)))
                .thenThrow(new TiktokApiException("Simulated TikTok API rate limit", 429, 429001, "req-1", true));

        // Tenant 2 thành công
        TiktokOrderDetailResponse successOrder = TiktokOrderDetailResponse.builder().orderId("SUCCESS-ORD").build();
        when(tiktokOrderClient.searchOrders(eq("CIPHER-SUCCESS"), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(successOrder)).build());

        // Scheduler vẫn chạy tiếp qua tenant 2
        int synced = scheduler.pollOrdersFromMarketplaces(30);

        assertEquals(1, synced);
        verify(ingestionService, never()).ingestOrder(eq(501L), any(), any());
        verify(ingestionService, times(1)).ingestOrder(eq(502L), eq(successOrder), eq(POLLING));
    }

    @Test
    @DisplayName("Lọc theo cửa sổ update_time từ checkpoint tới hiện tại và tiến checkpoint khi xong cả cửa sổ")
    void pollUsesUpdateTimeWindowFromCheckpointAndAdvancesIt() {
        MarketplaceConnection conn = createConnection(3L, 300L, MarketplaceConnection.Platform.TIKTOK_SHOP, "CIPHER-300");
        long checkpoint = java.time.Instant.now().getEpochSecond() - 600;
        when(checkpoints.find(3L, TiktokSyncCheckpointStore.ORDERS)).thenReturn(java.util.Optional.of(checkpoint));
        TiktokOrderDetailResponse order = TiktokOrderDetailResponse.builder().orderId("O-1").build();
        when(tiktokOrderClient.searchOrders(eq("CIPHER-300"), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(order)).build());

        long before = java.time.Instant.now().getEpochSecond();
        assertEquals(1, scheduler.pollOrdersForConnection(conn, 30));
        long after = java.time.Instant.now().getEpochSecond();

        ArgumentCaptor<OrderSearchRequest> request = ArgumentCaptor.forClass(OrderSearchRequest.class);
        verify(tiktokOrderClient).searchOrders(eq("CIPHER-300"), request.capture());
        assertEquals(checkpoint - OrderPollingScheduler.DEFAULT_OVERLAP_SECONDS, request.getValue().getUpdateTimeFrom());
        long to = request.getValue().getUpdateTimeTo();
        assertTrue(to >= before && to <= after);
        assertNull(request.getValue().getCreateTimeFrom());
        assertNull(request.getValue().getCreateTimeTo());
        verify(checkpoints).advance(300L, 3L, TiktokSyncCheckpointStore.ORDERS, to);
    }

    @Test
    @DisplayName("Lần chạy đầu (chưa có checkpoint) dùng now - lookback")
    void firstRunStartsAtNowMinusLookback() {
        MarketplaceConnection conn = createConnection(4L, 400L, MarketplaceConnection.Platform.TIKTOK_SHOP, "CIPHER-400");
        when(tiktokOrderClient.searchOrders(eq("CIPHER-400"), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of()).build());

        scheduler.pollOrdersForConnection(conn, 30);

        ArgumentCaptor<OrderSearchRequest> request = ArgumentCaptor.forClass(OrderSearchRequest.class);
        verify(tiktokOrderClient).searchOrders(eq("CIPHER-400"), request.capture());
        assertEquals(30 * 60L, request.getValue().getUpdateTimeTo() - request.getValue().getUpdateTimeFrom());
        verify(checkpoints).advance(eq(400L), eq(4L), eq(TiktokSyncCheckpointStore.ORDERS), eq(request.getValue().getUpdateTimeTo()));
    }

    @Test
    @DisplayName("Checkpoint không tiến khi một đơn ingest lỗi giữa chừng")
    void checkpointStaysWhenAnOrderFails() {
        MarketplaceConnection conn = createConnection(5L, 500L, MarketplaceConnection.Platform.TIKTOK_SHOP, "CIPHER-500");
        TiktokOrderDetailResponse ok = TiktokOrderDetailResponse.builder().orderId("OK").build();
        TiktokOrderDetailResponse bad = TiktokOrderDetailResponse.builder().orderId("BAD").build();
        when(tiktokOrderClient.searchOrders(eq("CIPHER-500"), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(bad, ok)).build());
        when(ingestionService.ingestOrder(500L, bad, POLLING)).thenThrow(new RuntimeException("queue down"));

        assertEquals(1, scheduler.pollOrdersForConnection(conn, 30));

        verify(ingestionService).ingestOrder(500L, ok, POLLING);
        verify(checkpoints, never()).advance(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Checkpoint không tiến khi một trang lỗi giữa chừng")
    void checkpointStaysWhenALaterPageFails() {
        MarketplaceConnection conn = createConnection(6L, 600L, MarketplaceConnection.Platform.TIKTOK_SHOP, "CIPHER-600");
        when(tiktokOrderClient.searchOrders(eq("CIPHER-600"), argThat(req -> req != null && req.getPageToken() == null)))
                .thenReturn(OrderSearchResponse.builder()
                        .orders(List.of(TiktokOrderDetailResponse.builder().orderId("P1").build()))
                        .nextPageToken("T2").build());
        when(tiktokOrderClient.searchOrders(eq("CIPHER-600"), argThat(req -> req != null && "T2".equals(req.getPageToken()))))
                .thenThrow(new TiktokApiException("timeout", 0, null, null, true));

        assertThrows(TiktokApiException.class, () -> scheduler.pollOrdersForConnection(conn, 30));

        verify(checkpoints, never()).advance(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Bỏ qua các kết nối sàn không phải TIKTOK_SHOP (ví dụ SHOPEE)")
    void pollOrdersFromMarketplaces_skipsNonTikTokConnections() {
        MarketplaceConnection shopeeConn = createConnection(99L, 999L, MarketplaceConnection.Platform.SHOPEE, "SHOPEE-1");
        when(connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED))
                .thenReturn(List.of(shopeeConn));

        int synced = scheduler.pollOrdersFromMarketplaces(30);

        assertEquals(0, synced);
        verifyNoInteractions(tiktokOrderClient);
        verifyNoInteractions(ingestionService);
    }

    @Test
    @DisplayName("Không có kết nối active trả về 0 và không gọi client")
    void pollOrdersFromMarketplaces_noActiveConnections() {
        when(connectionRepository.findByStatus(MarketplaceConnection.ConnectionStatus.CONNECTED))
                .thenReturn(Collections.emptyList());

        int synced = scheduler.pollOrdersFromMarketplaces();

        assertEquals(0, synced);
        verifyNoInteractions(tiktokOrderClient);
        verifyNoInteractions(ingestionService);
    }
}
