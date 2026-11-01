package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.security.WebhookHmacValidator;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.WebhookEventLog;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.repository.WebhookEventLogRepository;
import com.smartomni.integration.service.AesEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("TiktokWebhookController Unit Tests")
class TiktokWebhookControllerTest {

    private static final String WEBHOOK_PATH = "/api/v1/integrations/tiktok/webhooks";
    private static final String APP_SECRET = "test-tiktok-app-secret-12345";
    private static final String SHOP_CIPHER = "TEST_CIPHER_SHOP_999";

    @Mock
    private MarketplaceConnectionRepository connectionRepository;

    @Mock
    private WebhookEventLogRepository webhookEventLogRepository;

    @Mock
    private AesEncryptionService encryptionService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private WebhookHmacValidator webhookHmacValidator;
    private TiktokProperties properties;
    private ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        webhookHmacValidator = new WebhookHmacValidator();
        properties = new TiktokProperties();
        properties.setAppSecret(APP_SECRET);
        objectMapper = new ObjectMapper();

        TiktokWebhookController controller = new TiktokWebhookController(
                connectionRepository,
                webhookEventLogRepository,
                webhookHmacValidator,
                properties,
                encryptionService,
                rabbitTemplate,
                objectMapper
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("200 OK: Webhook hợp lệ với HMAC-SHA256 signature và timestamp trong tolerance")
    void testReceiveWebhook_Success_200Ok() throws Exception {
        Long tenantId = 100L;
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setId(10L);
        connection.setTenantId(tenantId);
        connection.setShopCipher(SHOP_CIPHER);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);

        when(connectionRepository.findFirstByShopCipher(SHOP_CIPHER)).thenReturn(Optional.of(connection));

        long timestamp = Instant.now().getEpochSecond();
        String rawBody = String.format("""
            {"type":1,"tts_notification_id":"notif-001","shop_id":"%s","timestamp":%d,"data":{"order_id":"ORD-999"}}
            """, SHOP_CIPHER, timestamp);

        String contentToSign = APP_SECRET + WEBHOOK_PATH + timestamp + rawBody;
        String validSignature = webhookHmacValidator.calculateHmacSha256(contentToSign, APP_SECRET);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", validSignature)
                        .header("Timestamp", String.valueOf(timestamp))
                        .content(rawBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"));

        // Verify audit log được ghi lại với signatureValid = true và queued = true
        ArgumentCaptor<WebhookEventLog> logCaptor = ArgumentCaptor.forClass(WebhookEventLog.class);
        verify(webhookEventLogRepository).save(logCaptor.capture());
        WebhookEventLog savedLog = logCaptor.getValue();
        assertNotNull(savedLog);
        assertEquals(tenantId, savedLog.getTenantId());
        assertEquals("notif-001", savedLog.getEventId());
        assertEquals("ORDER_STATUS_CHANGE", savedLog.getEventType());
        assertTrue(savedLog.getSignatureValid());
        assertTrue(savedLog.isQueued());

        // Verify RabbitMQ đã được gửi payload
        verify(rabbitTemplate).convertAndSend(eq("smartomni.order.exchange"), eq("order.incoming"), eq(rawBody));
    }

    @Test
    @DisplayName("401 Unauthorized: Chữ ký HMAC không hợp lệ hoặc bị can thiệp payload")
    void testReceiveWebhook_InvalidSignature_401Unauthorized() throws Exception {
        Long tenantId = 100L;
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setId(10L);
        connection.setTenantId(tenantId);
        connection.setShopCipher(SHOP_CIPHER);

        when(connectionRepository.findFirstByShopCipher(SHOP_CIPHER)).thenReturn(Optional.of(connection));

        long timestamp = Instant.now().getEpochSecond();
        String rawBody = String.format("""
            {"type":1,"tts_notification_id":"notif-002","shop_id":"%s","timestamp":%d,"data":{"order_id":"ORD-999"}}
            """, SHOP_CIPHER, timestamp);

        String invalidSignature = "invalid_hmac_signature_hex_123456";

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", invalidSignature)
                        .header("Timestamp", String.valueOf(timestamp))
                        .content(rawBody))
                .andExpect(status().isUnauthorized());

        // Verify audit log được lưu với signatureValid = false và queued = false
        ArgumentCaptor<WebhookEventLog> logCaptor = ArgumentCaptor.forClass(WebhookEventLog.class);
        verify(webhookEventLogRepository).save(logCaptor.capture());
        WebhookEventLog savedLog = logCaptor.getValue();
        assertNotNull(savedLog);
        assertFalse(savedLog.getSignatureValid());
        assertFalse(savedLog.isQueued());

        // Verify RabbitMQ KHÔNG được gửi
        verify(rabbitTemplate, never()).convertAndSend(any(), any(), any(Object.class));
    }

    @Test
    @DisplayName("401 Unauthorized: Bị từ chối khi thiếu header Authorization / X-Tts-Signature")
    void testReceiveWebhook_MissingSignatureHeader_401Unauthorized() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String rawBody = String.format("""
            {"type":1,"tts_notification_id":"notif-003","shop_id":"%s","timestamp":%d}
            """, SHOP_CIPHER, timestamp);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Timestamp", String.valueOf(timestamp))
                        .content(rawBody))
                .andExpect(status().isUnauthorized());

        verify(connectionRepository, never()).findFirstByShopCipher(any());
        verify(rabbitTemplate, never()).convertAndSend(any(), any(), any(Object.class));
    }

    @Test
    @DisplayName("200 OK: Webhook DEAUTHORIZATION marks connection REVOKED and logs audit")
    void testReceiveWebhook_Deauthorization_MarksRevoked() throws Exception {
        Long tenantId = 100L;
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setId(10L);
        connection.setTenantId(tenantId);
        connection.setShopCipher(SHOP_CIPHER);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(connectionRepository.findFirstByShopCipher(SHOP_CIPHER)).thenReturn(Optional.of(connection));

        long timestamp = Instant.now().getEpochSecond();
        String rawBody = String.format("""
            {"type":16,"tts_notification_id":"notif-deauth-001","shop_id":"%s","timestamp":%d,"data":{"event":"DEAUTHORIZATION"}}
            """, SHOP_CIPHER, timestamp);

        String contentToSign = APP_SECRET + WEBHOOK_PATH + timestamp + rawBody;
        String validSignature = webhookHmacValidator.calculateHmacSha256(contentToSign, APP_SECRET);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", validSignature)
                        .header("Timestamp", String.valueOf(timestamp))
                        .content(rawBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"));

        // Verify connection status is REVOKED
        assertEquals(MarketplaceConnection.ConnectionStatus.REVOKED, connection.getStatus());

        // Verify audit log has eventType DEAUTHORIZATION
        ArgumentCaptor<WebhookEventLog> logCaptor = ArgumentCaptor.forClass(WebhookEventLog.class);
        verify(webhookEventLogRepository).save(logCaptor.capture());
        WebhookEventLog savedLog = logCaptor.getValue();
        assertNotNull(savedLog);
        assertEquals("DEAUTHORIZATION", savedLog.getEventType());
        assertTrue(savedLog.getSignatureValid());
        assertFalse(savedLog.isQueued());

        // Verify RabbitMQ was NOT sent for deauth
        verify(rabbitTemplate, never()).convertAndSend(eq("smartomni.order.exchange"), eq("order.incoming"), any(Object.class));
    }

    @Test
    @DisplayName("401 Unauthorized: Từ chối khi Timestamp lệch quá 5 phút (Replay attack)")
    void testReceiveWebhook_TimestampSkewOver5Minutes_401Unauthorized() throws Exception {
        Long tenantId = 100L;
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setId(10L);
        connection.setTenantId(tenantId);
        connection.setShopCipher(SHOP_CIPHER);

        when(connectionRepository.findFirstByShopCipher(SHOP_CIPHER)).thenReturn(Optional.of(connection));

        // Giả lập timestamp cách đây 10 phút (> 300 giây)
        long oldTimestamp = Instant.now().getEpochSecond() - 600;
        String rawBody = String.format("""
            {"type":1,"tts_notification_id":"notif-004","shop_id":"%s","timestamp":%d,"data":{"order_id":"ORD-999"}}
            """, SHOP_CIPHER, oldTimestamp);

        String contentToSign = APP_SECRET + WEBHOOK_PATH + oldTimestamp + rawBody;
        String signature = webhookHmacValidator.calculateHmacSha256(contentToSign, APP_SECRET);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tts-Signature", signature)
                        .header("X-Tts-Timestamp", String.valueOf(oldTimestamp))
                        .content(rawBody))
                .andExpect(status().isUnauthorized());

        verify(rabbitTemplate, never()).convertAndSend(any(), any(), any(Object.class));
    }

    @Test
    @DisplayName("404 Not Found: Từ chối khi shop_id không tồn tại trong hệ thống")
    void testReceiveWebhook_UnknownShop_404NotFound() throws Exception {
        when(connectionRepository.findFirstByShopCipher("UNKNOWN_SHOP")).thenReturn(Optional.empty());
        when(connectionRepository.findFirstByShopId("UNKNOWN_SHOP")).thenReturn(Optional.empty());
        when(connectionRepository.findFirstBySellerOpenId("UNKNOWN_SHOP")).thenReturn(Optional.empty());

        long timestamp = Instant.now().getEpochSecond();
        String rawBody = String.format("""
            {"type":1,"tts_notification_id":"notif-005","shop_id":"UNKNOWN_SHOP","timestamp":%d}
            """, timestamp);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "any_signature")
                        .header("Timestamp", String.valueOf(timestamp))
                        .content(rawBody))
                .andExpect(status().isNotFound());

        verify(rabbitTemplate, never()).convertAndSend(any(), any(), any(Object.class));
    }
}
