package com.smartomni.integration.mq;

import com.smartomni.integration.service.order.ShopeeReturnSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeReturnMessageConsumer Unit Tests")
class ShopeeReturnMessageConsumerTest {

    private ShopeeReturnSyncService returnSyncService;
    private ShopeeReturnMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        returnSyncService = mock(ShopeeReturnSyncService.class);
        consumer = new ShopeeReturnMessageConsumer(returnSyncService);
    }

    @Test
    @DisplayName("Successfully consumes valid return message and calls syncReturn")
    void handleIncomingReturn_validMessage_success() {
        Map<String, Object> message = new HashMap<>();
        message.put("tenantId", 1L);
        message.put("shopId", "727720655");
        message.put("platformOrderId", "ORD-12345");
        message.put("returnSn", "RET-9901");

        consumer.handleIncomingReturn(message);

        verify(returnSyncService).syncReturn(1L, "727720655", "ORD-12345", "RET-9901");
    }

    @Test
    @DisplayName("Handles string tenantId correctly")
    void handleIncomingReturn_stringTenantId_success() {
        Map<String, Object> message = new HashMap<>();
        message.put("tenantId", "2");
        message.put("shopId", "727720655");
        message.put("platformOrderId", "ORD-12345");
        message.put("returnSn", "RET-9901");

        consumer.handleIncomingReturn(message);

        verify(returnSyncService).syncReturn(2L, "727720655", "ORD-12345", "RET-9901");
    }

    @Test
    @DisplayName("Ignores message if returnSn is missing")
    void handleIncomingReturn_missingReturnSn_ignored() {
        Map<String, Object> message = new HashMap<>();
        message.put("tenantId", 1L);
        message.put("platformOrderId", "ORD-12345");

        assertDoesNotThrow(() -> consumer.handleIncomingReturn(message));

        verifyNoInteractions(returnSyncService);
    }

    @Test
    @DisplayName("Ignores null message gracefully")
    void handleIncomingReturn_nullMessage_ignored() {
        assertDoesNotThrow(() -> consumer.handleIncomingReturn(null));
        verifyNoInteractions(returnSyncService);
    }

    @Test
    @DisplayName("Throws exception if tenantId is missing or invalid")
    void handleIncomingReturn_missingTenantId_throwsException() {
        Map<String, Object> message = new HashMap<>();
        message.put("returnSn", "RET-9901");

        assertThrows(IllegalArgumentException.class, () -> consumer.handleIncomingReturn(message));
        verifyNoInteractions(returnSyncService);
    }

    @Test
    @DisplayName("Propagates exception when syncReturn fails to allow RabbitMQ retry / DLQ")
    void handleIncomingReturn_syncFails_rethrowsException() {
        Map<String, Object> message = new HashMap<>();
        message.put("tenantId", 1L);
        message.put("returnSn", "RET-9901");

        doThrow(new RuntimeException("Shopee API timeout"))
                .when(returnSyncService).syncReturn(any(), any(), any(), any());

        assertThrows(RuntimeException.class, () -> consumer.handleIncomingReturn(message));
    }
}
