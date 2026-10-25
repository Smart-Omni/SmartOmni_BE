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

    @Mock
    private MarketplaceConnectionRepository connectionRepository;

    @Mock
    private TiktokOrderClient tiktokOrderClient;

    @Mock
    private TiktokOrderIngestionService ingestionService;

    private TransactionTemplate transactionTemplate;
    private OrderPollingScheduler scheduler;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        transactionTemplate = new TransactionTemplate(ptm);

        scheduler = new OrderPollingScheduler(
                connectionRepository,
                tiktokOrderClient,
                ingestionService,
                transactionTemplate
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
        verify(ingestionService).ingestOrder(100L, order1);
        verify(ingestionService).ingestOrder(100L, order2);
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
        verify(ingestionService).ingestOrder(200L, page1Order);
        verify(ingestionService).ingestOrder(200L, page2Order);

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
        verify(ingestionService, never()).ingestOrder(eq(501L), any());
        verify(ingestionService, times(1)).ingestOrder(eq(502L), eq(successOrder));
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
