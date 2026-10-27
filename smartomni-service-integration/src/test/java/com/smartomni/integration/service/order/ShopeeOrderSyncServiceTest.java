package com.smartomni.integration.service.order;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.shopee.ShopeeOrderClient;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderListResult;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderSummary;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.ShopeeOrderDetail;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.dto.order.ShopeeOrderPayload;
import com.smartomni.integration.entity.MarketplaceConnection;
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

@DisplayName("ShopeeOrderSyncService Unit Tests")
class ShopeeOrderSyncServiceTest {

    private ShopeeOrderClient orderClient;
    private ShopeeOrderMapper orderMapper;
    private RabbitTemplate rabbitTemplate;
    private ShopeeOrderSyncService service;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        orderClient = mock(ShopeeOrderClient.class);
        orderMapper = mock(ShopeeOrderMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);

        service = new ShopeeOrderSyncService(orderClient, orderMapper, rabbitTemplate);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setShopId("727720655");
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
    }

    @Test
    @DisplayName("syncOrder fetches detail, maps payload, and enqueues to RabbitMQ")
    void syncOrder_Success() {
        ShopeeOrderDetail detail = new ShopeeOrderDetail(
                "241027SP1042", "READY_TO_SHIP", "VND", new BigDecimal("450000"),
                "buyer1", 9911L, null, List.of(), 1714173300L, 1714000000L, 1714000500L,
                null, null, "SPX", "SPX123"
        );
        when(orderClient.getOrderDetail(88L, List.of("241027SP1042"))).thenReturn(List.of(detail));

        ShopeeOrderPayload payload = ShopeeOrderPayload.builder()
                .tenantId(1L)
                .platform("SHOPEE")
                .platformOrderId("241027SP1042")
                .status("TO_SHIP")
                .totalAmount(new BigDecimal("450000"))
                .build();
        when(orderMapper.toPayload(1L, 88L, detail)).thenReturn(payload);

        ShopeeOrderPayload result = service.syncOrder(connection, "241027SP1042");

        assertEquals("241027SP1042", result.getPlatformOrderId());
        assertEquals("TO_SHIP", result.getStatus());

        ArgumentCaptor<ShopeeOrderPayload> payloadCaptor = ArgumentCaptor.forClass(ShopeeOrderPayload.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ORDER_EXCHANGE),
                eq("order.incoming"),
                payloadCaptor.capture()
        );
        assertEquals("241027SP1042", payloadCaptor.getValue().getPlatformOrderId());
    }

    @Test
    @DisplayName("syncOrder throws ORDER_NOT_FOUND when Shopee detail response is empty")
    void syncOrder_NotFound_ThrowsException() {
        when(orderClient.getOrderDetail(88L, List.of("NON_EXISTENT"))).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.syncOrder(connection, "NON_EXISTENT")
        );
        assertEquals("ORDER_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("syncOrdersBatch retrieves list, details, and enqueues each order")
    void syncOrdersBatch_Success() {
        OrderListResult listResult = new OrderListResult(
                List.of(new OrderSummary("SP01", "READY_TO_SHIP"), new OrderSummary("SP02", "UNPAID")),
                false, "2"
        );
        when(orderClient.getOrderList(eq(88L), any(), any(), eq(50), isNull(), any())).thenReturn(listResult);

        ShopeeOrderDetail d1 = new ShopeeOrderDetail("SP01", "READY_TO_SHIP", "VND", BigDecimal.TEN, null, null, null, List.of(), null, null, null, null, null, null, null);
        ShopeeOrderDetail d2 = new ShopeeOrderDetail("SP02", "UNPAID", "VND", BigDecimal.ONE, null, null, null, List.of(), null, null, null, null, null, null, null);
        when(orderClient.getOrderDetail(88L, List.of("SP01", "SP02"))).thenReturn(List.of(d1, d2));

        ShopeeOrderPayload p1 = ShopeeOrderPayload.builder().platformOrderId("SP01").status("TO_SHIP").build();
        ShopeeOrderPayload p2 = ShopeeOrderPayload.builder().platformOrderId("SP02").status("PENDING").build();
        when(orderMapper.toPayload(1L, 88L, d1)).thenReturn(p1);
        when(orderMapper.toPayload(1L, 88L, d2)).thenReturn(p2);

        List<ShopeeOrderPayload> synced = service.syncOrdersBatch(connection, null, null, "ALL", 50);

        assertEquals(2, synced.size());
        verify(rabbitTemplate, times(2)).convertAndSend(eq(RabbitMQConfig.ORDER_EXCHANGE), eq("order.incoming"), any(ShopeeOrderPayload.class));
    }

    @Test
    @DisplayName("cancelOrder forwards request to ShopeeOrderClient")
    void cancelOrder_Success() {
        when(orderClient.cancelOrder(88L, "241027SP1042", "OUT_OF_STOCK")).thenReturn(true);

        boolean success = service.cancelOrder(connection, "241027SP1042", "OUT_OF_STOCK");
        assertTrue(success);
        verify(orderClient).cancelOrder(88L, "241027SP1042", "OUT_OF_STOCK");
    }

    @Test
    @DisplayName("handleBuyerCancellation forwards request to ShopeeOrderClient")
    void handleBuyerCancellation_Success() {
        when(orderClient.handleBuyerCancellation(88L, "241027SP1042", "ACCEPT")).thenReturn(true);

        boolean success = service.handleBuyerCancellation(connection, "241027SP1042", "ACCEPT");
        assertTrue(success);
        verify(orderClient).handleBuyerCancellation(88L, "241027SP1042", "ACCEPT");
    }
}
