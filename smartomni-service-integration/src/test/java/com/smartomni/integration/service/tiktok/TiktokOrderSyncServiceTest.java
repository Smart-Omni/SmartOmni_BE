package com.smartomni.integration.service.tiktok;

import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokFulfillmentClient;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderSyncResponse;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The manual sync publishes to the shared order queue instead of writing {@code orders} itself (plan 4.4, M12/M13).
 */
@DisplayName("TiktokOrderSyncService: manual sync goes through order.incoming")
class TiktokOrderSyncServiceTest {

    private TiktokOrderClient orderClient;
    private RabbitTemplate rabbitTemplate;
    private TiktokOrderSyncService syncService;
    private MarketplaceConnection connection;
    private TiktokFulfillmentClient fulfillmentClient;

    @BeforeEach
    void setUp() {
        orderClient = mock(TiktokOrderClient.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        CatalogMappingService catalogMappingService = mock(CatalogMappingService.class);
        TiktokOrderIngestionService ingestion = new TiktokOrderIngestionService(
                catalogMappingService, rabbitTemplate, new ObjectMapper(), orderClient);
        fulfillmentClient = mock(TiktokFulfillmentClient.class);
        syncService = new TiktokOrderSyncService(orderClient, ingestion, new TiktokTrackingEnricher(fulfillmentClient));

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(10L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
    }

    private static TiktokOrderDetailResponse order(String id, String status, long updateTime) {
        return TiktokOrderDetailResponse.builder()
                .orderId(id)
                .orderStatus(status)
                .createTime(1699900000L)
                .updateTime(updateTime)
                .payment(TiktokOrderDetailResponse.Payment.builder().totalAmount(new BigDecimal("250000")).build())
                .lineItems(List.of(TiktokOrderDetailResponse.LineItem.builder()
                        .skuId("TK-SKU-1").sellerSku("LOCAL-SKU-1").quantity(1).salePrice(new BigDecimal("250000")).build()))
                .build();
    }

    private List<WebhookOrderPayload> published(int times) {
        ArgumentCaptor<WebhookOrderPayload> captor = ArgumentCaptor.forClass(WebhookOrderPayload.class);
        verify(rabbitTemplate, times(times)).convertAndSend(
                eq(TiktokOrderIngestionService.ORDER_EXCHANGE), eq(TiktokOrderIngestionService.ORDER_ROUTING_KEY), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("A DELIVERED order is published raw with source POLLING (order-service maps it to DELIVERING)")
    void deliveredOrderIsPublishedRawWithPollingSource() {
        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(order("ORD-1", "DELIVERED", 1700000500L))).build());

        TiktokOrderSyncResponse response = syncService.syncOrders(connection, null, null);

        assertEquals(1, response.getTotalFetched());
        assertEquals(1, response.getEnqueuedCount());
        assertEquals(0, response.getFailedCount());
        WebhookOrderPayload payload = published(1).get(0);
        assertEquals(10L, payload.getTenantId());
        assertEquals("TIKTOK_SHOP", payload.getPlatform());
        assertEquals("ORD-1", payload.getPlatformOrderId());
        assertEquals("DELIVERED", payload.getOrderStatus());
        assertEquals(1700000500L, payload.getPlatformUpdateTime());
        assertEquals("POLLING", payload.getSource());
    }

    @Test
    @DisplayName("A shipped order is published with the tracking number read from its package")
    void shippedOrderCarriesPackageTracking() {
        TiktokOrderDetailResponse shipped = order("ORD-7", "AWAITING_COLLECTION", 1700000800L);
        shipped.setPackageList(List.of(TiktokOrderDetailResponse.OrderPackage.builder().packageId("PKG-7").build()));
        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(shipped)).build());
        when(fulfillmentClient.getPackageDetail(77L, "PKG-7")).thenReturn(TiktokPackageResponse.builder()
                .trackingNumber("SIM7").shippingProviderName("Express").build());

        syncService.syncOrders(connection, null, null);

        WebhookOrderPayload payload = published(1).get(0);
        assertEquals("SIM7", payload.getTrackingNumber());
        assertEquals("Express", payload.getShippingProvider());
    }

    @Test
    @DisplayName("Syncing the same orders twice publishes them twice; order-service dedups on tenant+platform+order id")
    void repeatedSyncOnlyRepublishes() {
        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(order("ORD-2", "COMPLETED", 1700000600L))).build());

        syncService.syncOrders(connection, null, null);
        syncService.syncOrders(connection, null, null);

        List<WebhookOrderPayload> payloads = published(2);
        assertEquals(payloads.get(0).getPlatformOrderId(), payloads.get(1).getPlatformOrderId());
    }

    @Test
    @DisplayName("Order detail is read only when line items are missing (no completed_time check, M13)")
    void detailOnlyWhenItemsMissing() {
        TiktokOrderDetailResponse withoutItems = TiktokOrderDetailResponse.builder()
                .orderId("ORD-3").orderStatus("COMPLETED").updateTime(1700000700L).build();
        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder()
                        .orders(List.of(order("ORD-4", "COMPLETED", 1700000800L), withoutItems)).build());
        when(orderClient.getOrderDetail(77L, "ORD-3")).thenReturn(order("ORD-3", "COMPLETED", 1700000700L));

        TiktokOrderSyncResponse response = syncService.syncOrders(connection, null, null);

        assertEquals(2, response.getEnqueuedCount());
        verify(orderClient, never()).getOrderDetail(77L, "ORD-4");
        verify(orderClient).getOrderDetail(77L, "ORD-3");
    }

    @Test
    @DisplayName("A failed publish is counted and does not stop the other orders")
    void publishFailureIsCounted() {
        when(orderClient.searchOrders(eq(77L), any(OrderSearchRequest.class)))
                .thenReturn(OrderSearchResponse.builder()
                        .orders(List.of(order("ORD-5", "AWAITING_SHIPMENT", 1L), order("ORD-6", "AWAITING_SHIPMENT", 2L))).build());
        doThrow(new RuntimeException("broker down")).doNothing()
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(WebhookOrderPayload.class));

        TiktokOrderSyncResponse response = syncService.syncOrders(connection, null, null);

        assertEquals(1, response.getEnqueuedCount());
        assertEquals(1, response.getFailedCount());
    }

    @Test
    @DisplayName("Paginates with next_page_token and stops on a repeated token")
    void paginates() {
        when(orderClient.searchOrders(eq(77L), argThat(r -> r != null && r.getPageToken() == null)))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(order("P1", "UNPAID", 1L))).nextPageToken("T1").build());
        when(orderClient.searchOrders(eq(77L), argThat(r -> r != null && "T1".equals(r.getPageToken()))))
                .thenReturn(OrderSearchResponse.builder().orders(List.of(order("P2", "UNPAID", 2L))).nextPageToken("T1").build());

        assertEquals(2, syncService.syncOrders(connection, null, null).getEnqueuedCount());
    }

    @Test
    void connectionIsRequired() {
        assertThrows(BusinessException.class, () -> syncService.syncOrders(null, null, null));
        verifyNoInteractions(rabbitTemplate);
    }
}
