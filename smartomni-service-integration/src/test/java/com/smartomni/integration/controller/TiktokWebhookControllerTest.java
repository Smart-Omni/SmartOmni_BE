package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.security.WebhookHmacValidator;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.AesEncryptionService;
import com.smartomni.integration.service.tiktok.TiktokFulfillmentService;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;

import com.smartomni.integration.service.tiktok.TiktokWebhookEventRecorder;
import com.smartomni.integration.service.tiktok.TiktokWebhookEventRecorder.Claim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TiktokWebhookController Unit Tests")
class TiktokWebhookControllerTest {

    private static final String WEBHOOK_PATH = "/api/v1/integrations/tiktok/webhooks";
    private static final String APP_SECRET = "test-tiktok-app-secret-12345";
    private static final String SHOP_CIPHER = "TEST_CIPHER_SHOP_999";
    private static final long TENANT_ID = 100L;

    @Mock private MarketplaceConnectionRepository connectionRepository;
    @Mock private TiktokWebhookEventRecorder eventRecorder;
    @Mock private AesEncryptionService encryptionService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private TiktokOrderIngestionService orderIngestionService;
    @Mock private com.smartomni.integration.service.tiktok.TiktokAftersaleWebhookService aftersaleService;
    @Mock private TiktokFulfillmentService fulfillmentService;

    private WebhookHmacValidator webhookHmacValidator;
    private MarketplaceConnection connection;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        webhookHmacValidator = new WebhookHmacValidator();
        TiktokProperties properties = new TiktokProperties();
        properties.setAppSecret(APP_SECRET);

        TiktokWebhookController controller = new TiktokWebhookController(connectionRepository, eventRecorder,
                webhookHmacValidator, properties, encryptionService, rabbitTemplate, new ObjectMapper(), orderIngestionService);
        controller.setAftersaleService(aftersaleService);
        controller.setFulfillmentService(fulfillmentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        connection = new MarketplaceConnection();
        connection.setId(10L);
        connection.setTenantId(TENANT_ID);
        connection.setShopCipher(SHOP_CIPHER);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        when(connectionRepository.findFirstByShopCipher(SHOP_CIPHER)).thenReturn(Optional.of(connection));
        when(eventRecorder.claim(anyLong(), anyString(), anyString(), anyString())).thenReturn(Claim.NEW);
    }

    private static String body(int type, String notificationId, String data) {
        String id = notificationId == null ? "" : "\"tts_notification_id\":\"" + notificationId + "\",";
        return "{\"type\":" + type + "," + id + "\"shop_id\":\"" + SHOP_CIPHER + "\",\"timestamp\":"
                + Instant.now().getEpochSecond() + ",\"data\":" + data + "}";
    }

    private ResultActions send(String body) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signature = webhookHmacValidator.calculateHmacSha256(APP_SECRET + WEBHOOK_PATH + timestamp + body, APP_SECRET);
        return mockMvc.perform(post(WEBHOOK_PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", signature).header("Timestamp", String.valueOf(timestamp)).content(body));
    }

    @Test
    @DisplayName("Type 1: lấy chi tiết đơn qua ingestion service, không còn đẩy raw JSON vào order.incoming")
    void orderStatusChangeFetchesOrderDetail() throws Exception {
        String body = body(1, "notif-001", "{\"order_id\":\"ORD-999\",\"order_status\":\"AWAITING_SHIPMENT\"}");

        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(eventRecorder).claim(TENANT_ID, "notif-001", "ORDER_STATUS_CHANGE", body);
        verify(orderIngestionService).processOrderEvent(eq(TENANT_ID), eq(connection), any(JsonNode.class));
        verify(rabbitTemplate, never()).convertAndSend(eq(TiktokWebhookController.ORDER_EXCHANGE),
                eq(TiktokWebhookController.ORDER_ROUTING_KEY), any(Object.class));
        verify(eventRecorder).markProcessed(TENANT_ID, "notif-001", "ORDER_STATUS_CHANGE");
    }

    @Test
    @DisplayName("Tra shop_id chạy không tenant, với role có policy đọc marketplace_connections (RLS)")
    void shopLookupRunsWithCrossTenantReadRole() throws Exception {
        when(connectionRepository.findFirstByShopCipher(SHOP_CIPHER)).thenAnswer(inv -> {
            assertEquals(null, com.smartomni.common.tenant.TenantContext.getTenantId());
            assertEquals("price_sync_worker", com.smartomni.common.tenant.TenantContext.getCurrentRole());
            return Optional.of(connection);
        });

        send(body(1, "notif-rls", "{\"order_id\":\"ORD-1\"}")).andExpect(status().isOk());

        assertEquals(null, com.smartomni.common.tenant.TenantContext.getCurrentRole());
    }

    @Test
    @DisplayName("Type 1: lỗi tạm thời từ TikTok trả 503 và không đánh dấu đã xử lý (để sàn gửi lại)")
    void orderStatusChangeTransientFailureIs503() throws Exception {
        doThrow(new TiktokApiException("timeout", 0, null, null, true))
                .when(orderIngestionService).processOrderEvent(eq(TENANT_ID), eq(connection), any());

        send(body(1, "notif-002", "{\"order_id\":\"ORD-1\"}")).andExpect(status().isServiceUnavailable());

        verify(eventRecorder, never()).markProcessed(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("Type 1: không đẩy được vào hàng đợi cũng trả 503")
    void orderStatusChangeQueueFailureIs503() throws Exception {
        doThrow(new BusinessException("queue down", HttpStatus.INTERNAL_SERVER_ERROR, "QUEUE_PUBLISH_FAILED"))
                .when(orderIngestionService).processOrderEvent(eq(TENANT_ID), eq(connection), any());

        send(body(1, "notif-003", "{\"order_id\":\"ORD-1\"}")).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("Cùng tts_notification_id chỉ xử lý một lần")
    void duplicateNotificationIsProcessedOnce() throws Exception {
        String body = body(1, "notif-dup", "{\"order_id\":\"ORD-1\"}");
        when(eventRecorder.claim(TENANT_ID, "notif-dup", "ORDER_STATUS_CHANGE", body))
                .thenReturn(Claim.NEW, Claim.DUPLICATE);

        send(body).andExpect(status().isOk());
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(orderIngestionService, times(1)).processOrderEvent(eq(TENANT_ID), eq(connection), any());
    }

    @Test
    @DisplayName("Lần gửi lại của event chưa xử lý xong (RETRY) vẫn được xử lý")
    void retryOfUnprocessedNotificationIsHandledAgain() throws Exception {
        when(eventRecorder.claim(anyLong(), eq("notif-retry"), anyString(), anyString())).thenReturn(Claim.RETRY);

        send(body(1, "notif-retry", "{\"order_id\":\"ORD-1\"}")).andExpect(status().isOk());

        verify(orderIngestionService).processOrderEvent(eq(TENANT_ID), eq(connection), any());
    }

    @Test
    @DisplayName("Thiếu tts_notification_id trả 400")
    void missingNotificationIdIs400() throws Exception {
        send(body(1, null, "{\"order_id\":\"ORD-1\"}")).andExpect(status().isBadRequest());

        verify(eventRecorder, never()).claim(anyLong(), any(), any(), any());
        verifyNoInteractions(orderIngestionService);
    }

    @Test
    @DisplayName("Type 6 SELLER_DEAUTHORIZATION thu hồi kết nối")
    void sellerDeauthorizationRevokesConnection() throws Exception {
        send(body(6, "notif-deauth", "{\"shop_id\":\"" + SHOP_CIPHER + "\",\"deauthorized_time\":1800000000}"))
                .andExpect(status().isOk());

        assertEquals(MarketplaceConnection.ConnectionStatus.REVOKED, connection.getStatus());
        verify(eventRecorder).markProcessed(TENANT_ID, "notif-deauth", "SELLER_DEAUTHORIZATION");
        verifyNoInteractions(orderIngestionService);
    }

    @Test
    @DisplayName("Type cũ không có trong bảng nhưng payload ghi DEAUTHORIZATION vẫn thu hồi kết nối")
    void legacyDeauthorizationTextIsStillRecognized() throws Exception {
        send(body(16, "notif-legacy", "{\"event\":\"DEAUTHORIZATION\"}")).andExpect(status().isOk());

        assertEquals(MarketplaceConnection.ConnectionStatus.REVOKED, connection.getStatus());
    }

    @Test
    @DisplayName("Type 11 tới handler hủy đơn, type 12 tới handler trả hàng")
    void cancellationAndReturnStatusChangesGoToTheAftersaleHandler() throws Exception {
        send(body(11, "notif-11", "{\"cancel_id\":\"C1\"}")).andExpect(status().isOk());
        send(body(12, "notif-12", "{\"return_id\":\"R1\"}")).andExpect(status().isOk());

        verify(aftersaleService).handleCancellationEvent(eq(TENANT_ID), eq(connection), any(JsonNode.class));
        verify(aftersaleService).handleReturnEvent(eq(TENANT_ID), eq(connection), any(JsonNode.class));
        verify(eventRecorder).markProcessed(TENANT_ID, "notif-11", "CANCELLATION_STATUS_CHANGE");
        verify(eventRecorder).markProcessed(TENANT_ID, "notif-12", "RETURN_STATUS_CHANGE");
        verifyNoInteractions(orderIngestionService);
        assertEquals(MarketplaceConnection.ConnectionStatus.CONNECTED, connection.getStatus());
    }

    @Test
    @DisplayName("Type 12: bản ghi chưa có trên sàn hoặc order service lỗi trả 503 để sàn gửi lại")
    void returnEventFailureIs503() throws Exception {
        doThrow(new BusinessException("not yet", HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_RETURN_NOT_READY"))
                .when(aftersaleService).handleReturnEvent(eq(TENANT_ID), eq(connection), any());

        send(body(12, "notif-12b", "{\"return_id\":\"R1\"}")).andExpect(status().isServiceUnavailable());

        verify(eventRecorder, never()).markProcessed(anyLong(), eq("notif-12b"), anyString());
    }

    @Test
    @DisplayName("Type 4 REVERSE_STATUS_UPDATE dùng chung handler trả hàng; type 2 (đổi địa chỉ) không")
    void reverseStatusGoesToReturnHandlerAndAddressUpdateDoesNot() throws Exception {
        send(body(4, "notif-4", "{\"return_id\":\"R1\"}")).andExpect(status().isOk());
        send(body(2, "notif-2", "{\"order_id\":\"O1\"}")).andExpect(status().isOk());

        verify(aftersaleService, times(1)).handleReturnEvent(eq(TENANT_ID), eq(connection), any(JsonNode.class));
    }

    @Test
    @DisplayName("Type 3 PACKAGE_UPDATE chạy trong tenant scope của shop (order service cần token nội bộ)")
    void packageUpdateRunsInTenantScope() throws Exception {
        when(fulfillmentService.handlePackageStatusChange(eq(TENANT_ID), eq(10L), any(JsonNode.class))).thenAnswer(inv -> {
            assertEquals(TENANT_ID, com.smartomni.common.tenant.TenantContext.getTenantId());
            return null;
        });

        send(body(3, "notif-3", "{\"package_id\":\"P1\",\"order_id\":\"O1\"}")).andExpect(status().isOk());

        verify(fulfillmentService).handlePackageStatusChange(eq(TENANT_ID), eq(10L), any(JsonNode.class));
        verify(eventRecorder).markProcessed(TENANT_ID, "notif-3", "PACKAGE_UPDATE");
        assertEquals(null, com.smartomni.common.tenant.TenantContext.getTenantId());
    }

    @Test
    @DisplayName("Type 3 PACKAGE_UPDATE: lỗi order service trả 503 để TikTok gửi lại, không đánh dấu đã xử lý")
    void packageUpdateFailureAsksForRedelivery() throws Exception {
        when(fulfillmentService.handlePackageStatusChange(eq(TENANT_ID), eq(10L), any(JsonNode.class)))
                .thenThrow(new IllegalStateException("order service down"));

        send(body(3, "notif-3b", "{\"package_id\":\"P1\",\"order_id\":\"O1\"}")).andExpect(status().isServiceUnavailable());

        verify(eventRecorder, never()).markProcessed(TENANT_ID, "notif-3b", "PACKAGE_UPDATE");
    }

    @Test
    void inventorySnapshotGoesToInventoryQueueOnly() throws Exception {
        String body = body(71, "inventory-1", "{\"sku_id\":\"sku-1\",\"quantity_snapshot_after_change\":{\"available_quantity\":0}}");

        send(body).andExpect(status().isOk());

        verify(rabbitTemplate).convertAndSend(TiktokWebhookController.ORDER_EXCHANGE, RabbitMQConfig.INVENTORY_ROUTING_KEY,
                Map.of("tenantId", TENANT_ID, "connectionId", 10L, "payload", body));
        verifyNoInteractions(orderIngestionService);
    }

    @Test
    @DisplayName("401: chữ ký sai; ghi audit không giữ event_id")
    void invalidSignatureIs401() throws Exception {
        String body = body(1, "notif-bad", "{\"order_id\":\"ORD-999\"}");

        mockMvc.perform(post(WEBHOOK_PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "invalid_hmac_signature_hex_123456")
                        .header("Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(eventRecorder).recordRejected(TENANT_ID, "ORDER_STATUS_CHANGE", body);
        verify(eventRecorder, never()).claim(anyLong(), any(), any(), any());
        verifyNoInteractions(orderIngestionService, rabbitTemplate);
    }

    @Test
    @DisplayName("401: thiếu header chữ ký")
    void missingSignatureHeaderIs401() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .content(body(1, "notif-003", "{}")))
                .andExpect(status().isUnauthorized());

        verify(connectionRepository, never()).findFirstByShopCipher(any());
    }

    @Test
    @DisplayName("401: timestamp lệch quá 5 phút")
    void timestampSkewOver5MinutesIs401() throws Exception {
        long oldTimestamp = Instant.now().getEpochSecond() - 600;
        String body = body(1, "notif-004", "{\"order_id\":\"ORD-999\"}");
        String signature = webhookHmacValidator.calculateHmacSha256(APP_SECRET + WEBHOOK_PATH + oldTimestamp + body, APP_SECRET);

        mockMvc.perform(post(WEBHOOK_PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tts-Signature", signature).header("X-Tts-Timestamp", String.valueOf(oldTimestamp))
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderIngestionService);
    }

    @Test
    @DisplayName("404: shop_id không tồn tại")
    void unknownShopIs404() throws Exception {
        when(connectionRepository.findFirstByShopCipher("UNKNOWN_SHOP")).thenReturn(Optional.empty());
        when(connectionRepository.findFirstByShopId("UNKNOWN_SHOP")).thenReturn(Optional.empty());
        when(connectionRepository.findFirstBySellerOpenId("UNKNOWN_SHOP")).thenReturn(Optional.empty());

        mockMvc.perform(post(WEBHOOK_PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "any_signature")
                        .header("Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .content("{\"type\":1,\"tts_notification_id\":\"n\",\"shop_id\":\"UNKNOWN_SHOP\",\"timestamp\":1}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(orderIngestionService);
    }
}
