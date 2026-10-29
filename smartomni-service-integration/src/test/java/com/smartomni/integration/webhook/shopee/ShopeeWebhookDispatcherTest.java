package com.smartomni.integration.webhook.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeWebhookDispatcher Unit Tests")
class ShopeeWebhookDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private MarketplaceConnectionRepository repository;
    private RabbitTemplate rabbitTemplate;
    private ObjectMapper objectMapper;
    private ShopeeWebhookDispatcher dispatcher;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        repository = mock(MarketplaceConnectionRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        dispatcher = new ShopeeWebhookDispatcher(repository, rabbitTemplate, objectMapper, clock);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setShopId("727720655");
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        connection.setAccessTokenEncrypted("access-tok");
        connection.setRefreshTokenEncrypted("refresh-tok");

        when(repository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(connection));
    }

    @Test
    @DisplayName("Code 1: shop_authorization_push re-enables connected status and clears error")
    void dispatch_Code1_HandlesAuthorization() throws Exception {
        connection.setStatus(MarketplaceConnection.ConnectionStatus.ERROR);
        connection.setLastErrorCode("SOME_ERROR");

        String raw = "{\"code\":1,\"shop_id\":727720655,\"timestamp\":1714000500,\"data\":{}}";
        ShopeeWebhookEvent event = ShopeeWebhookEvent.builder()
                .code(1)
                .shopId(727720655L)
                .timestamp(1714000500L)
                .data(objectMapper.readTree("{}"))
                .build();

        ShopeeWebhookDispatcher.DispatchOutcome outcome = dispatcher.dispatch(1L, event, raw);

        assertEquals(ShopeeWebhookDispatcher.DispatchOutcome.HANDLED_AUTH, outcome);
        assertEquals(MarketplaceConnection.ConnectionStatus.CONNECTED, connection.getStatus());
        assertNull(connection.getLastErrorCode());
        verify(repository).save(connection);
    }

    @Test
    @DisplayName("Code 2: shop_deauthorization_push marks connection REVOKED and clears tokens")
    void dispatch_Code2_HandlesDeauthorization() throws Exception {
        String raw = "{\"code\":2,\"shop_id\":727720655,\"timestamp\":1714000500,\"data\":{}}";
        ShopeeWebhookEvent event = ShopeeWebhookEvent.builder()
                .code(2)
                .shopId(727720655L)
                .timestamp(1714000500L)
                .data(objectMapper.readTree("{}"))
                .build();

        ShopeeWebhookDispatcher.DispatchOutcome outcome = dispatcher.dispatch(1L, event, raw);

        assertEquals(ShopeeWebhookDispatcher.DispatchOutcome.HANDLED_DEAUTH, outcome);
        assertEquals(MarketplaceConnection.ConnectionStatus.REVOKED, connection.getStatus());
        assertEquals("SHOP_DEAUTHORIZED", connection.getLastErrorCode());
        assertEquals(NOW, connection.getLastErrorAt());
        assertNull(connection.getAccessTokenEncrypted());
        assertNull(connection.getRefreshTokenEncrypted());
        verify(repository).save(connection);
    }

    @Test
    @DisplayName("Code 3: order_status_push publishes order message to RabbitMQ")
    @SuppressWarnings("unchecked")
    void dispatch_Code3_EnqueuesOrderEvent() throws Exception {
        String dataJson = "{\"ordersn\":\"261019TEST01\",\"status\":\"READY_TO_SHIP\",\"update_time\":1714000500}";
        String raw = "{\"code\":3,\"shop_id\":727720655,\"timestamp\":1714000500,\"data\":" + dataJson + "}";

        ShopeeWebhookEvent event = ShopeeWebhookEvent.builder()
                .code(3)
                .shopId(727720655L)
                .timestamp(1714000500L)
                .data(objectMapper.readTree(dataJson))
                .build();

        ShopeeWebhookDispatcher.DispatchOutcome outcome = dispatcher.dispatch(1L, event, raw);

        assertEquals(ShopeeWebhookDispatcher.DispatchOutcome.ENQUEUED_ORDER, outcome);

        ArgumentCaptor<Map<String, Object>> msgCaptor = ArgumentCaptor.forClass(Map.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ORDER_EXCHANGE),
                eq("order.incoming"),
                msgCaptor.capture()
        );

        Map<String, Object> sent = msgCaptor.getValue();
        assertEquals(1L, sent.get("tenantId"));
        assertEquals("SHOPEE", sent.get("platform"));
        assertEquals("261019TEST01", sent.get("platformOrderId"));
        assertEquals("READY_TO_SHIP", sent.get("status"));
        assertEquals("727720655", sent.get("shopId"));
    }

    @Test
    @DisplayName("Code 3: triggers direct ShopeeOrderSyncService when available")
    void dispatch_Code3_TriggersDirectSync() throws Exception {
        com.smartomni.integration.service.order.ShopeeOrderSyncService syncService =
                mock(com.smartomni.integration.service.order.ShopeeOrderSyncService.class);
        ShopeeWebhookDispatcher customDispatcher =
                new ShopeeWebhookDispatcher(repository, rabbitTemplate, objectMapper, syncService, Clock.fixed(NOW, ZoneOffset.UTC));

        String dataJson = "{\"ordersn\":\"261019TEST01\",\"status\":\"READY_TO_SHIP\"}";
        String raw = "{\"code\":3,\"shop_id\":727720655,\"timestamp\":1714000500,\"data\":" + dataJson + "}";
        ShopeeWebhookEvent event = ShopeeWebhookEvent.builder()
                .code(3)
                .shopId(727720655L)
                .timestamp(1714000500L)
                .data(objectMapper.readTree(dataJson))
                .build();

        ShopeeWebhookDispatcher.DispatchOutcome outcome = customDispatcher.dispatch(1L, event, raw);

        assertEquals(ShopeeWebhookDispatcher.DispatchOutcome.ENQUEUED_ORDER, outcome);
        verify(syncService).syncOrder(connection, "261019TEST01");
    }

    @Test
    @DisplayName("Code 4: order_tracking_no_push publishes tracking message to RabbitMQ")
    @SuppressWarnings("unchecked")
    void dispatch_Code4_EnqueuesTrackingEvent() throws Exception {
        String dataJson = "{\"ordersn\":\"261019TEST01\",\"tracking_no\":\"SPX_VN_998877\"}";
        String raw = "{\"code\":4,\"shop_id\":727720655,\"timestamp\":1714000500,\"data\":" + dataJson + "}";

        ShopeeWebhookEvent event = ShopeeWebhookEvent.builder()
                .code(4)
                .shopId(727720655L)
                .timestamp(1714000500L)
                .data(objectMapper.readTree(dataJson))
                .build();

        ShopeeWebhookDispatcher.DispatchOutcome outcome = dispatcher.dispatch(1L, event, raw);

        assertEquals(ShopeeWebhookDispatcher.DispatchOutcome.ENQUEUED_TRACKING, outcome);

        ArgumentCaptor<Map<String, Object>> msgCaptor = ArgumentCaptor.forClass(Map.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ORDER_EXCHANGE),
                eq("order.tracking"),
                msgCaptor.capture()
        );

        Map<String, Object> sent = msgCaptor.getValue();
        assertEquals("261019TEST01", sent.get("platformOrderId"));
        assertEquals("SPX_VN_998877", sent.get("trackingNo"));
    }

    @Test
    @DisplayName("Code 29: return_push publishes return message to RabbitMQ")
    @SuppressWarnings("unchecked")
    void dispatch_Code29_EnqueuesReturnEvent() throws Exception {
        String dataJson = "{\"order_sn\":\"261019TEST01\",\"return_sn\":\"RET_9901\"}";
        String raw = "{\"code\":29,\"shop_id\":727720655,\"timestamp\":1714000500,\"data\":" + dataJson + "}";

        ShopeeWebhookEvent event = ShopeeWebhookEvent.builder()
                .code(29)
                .shopId(727720655L)
                .timestamp(1714000500L)
                .data(objectMapper.readTree(dataJson))
                .build();

        ShopeeWebhookDispatcher.DispatchOutcome outcome = dispatcher.dispatch(1L, event, raw);

        assertEquals(ShopeeWebhookDispatcher.DispatchOutcome.ENQUEUED_RETURN, outcome);

        ArgumentCaptor<Map<String, Object>> msgCaptor = ArgumentCaptor.forClass(Map.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.ORDER_EXCHANGE),
                eq("return.incoming"),
                msgCaptor.capture()
        );

        Map<String, Object> sent = msgCaptor.getValue();
        assertEquals("RET_9901", sent.get("returnSn"));
        assertEquals("261019TEST01", sent.get("platformOrderId"));
    }

    @Test
    @DisplayName("Unknown code acknowledged with IGNORED")
    void dispatch_UnknownCode_ReturnsIgnored() throws Exception {
        ShopeeWebhookEvent event = ShopeeWebhookEvent.builder()
                .code(999)
                .shopId(727720655L)
                .timestamp(1714000500L)
                .data(objectMapper.readTree("{}"))
                .build();

        ShopeeWebhookDispatcher.DispatchOutcome outcome = dispatcher.dispatch(1L, event, "{}");

        assertEquals(ShopeeWebhookDispatcher.DispatchOutcome.IGNORED, outcome);
        verifyNoInteractions(rabbitTemplate);
    }
}
