package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.AesEncryptionService;
import com.smartomni.integration.service.WebhookSignatureValidator;
import com.smartomni.integration.webhook.shopee.ShopeeWebhookDispatcher;
import com.smartomni.integration.webhook.shopee.ShopeeWebhookEvent;
import com.smartomni.integration.webhook.shopee.ShopeeWebhookValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("WebhookController Unit Tests")
class WebhookControllerTest {

    private MarketplaceConnectionRepository repository;
    private AesEncryptionService encryptionService;
    private WebhookSignatureValidator genericSignatureValidator;
    private ShopeeProperties shopeeProperties;
    private ShopeeWebhookValidator shopeeWebhookValidator;
    private ShopeeWebhookDispatcher shopeeWebhookDispatcher;
    private RabbitTemplate rabbitTemplate;
    private ObjectMapper objectMapper;
    private HttpServletRequest request;

    private WebhookController controller;
    private MarketplaceConnection shopeeConnection;
    private MarketplaceConnection tiktokConnection;

    @BeforeEach
    void setUp() {
        repository = mock(MarketplaceConnectionRepository.class);
        encryptionService = mock(AesEncryptionService.class);
        genericSignatureValidator = mock(WebhookSignatureValidator.class);
        shopeeProperties = new ShopeeProperties();
        shopeeProperties.setPartnerKey("mock_key_123");
        shopeeWebhookValidator = mock(ShopeeWebhookValidator.class);
        shopeeWebhookDispatcher = mock(ShopeeWebhookDispatcher.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        objectMapper = new ObjectMapper();
        request = mock(HttpServletRequest.class);

        when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/webhook/1/shopee"));
        when(request.getRequestURI()).thenReturn("/webhook/1/shopee");

        shopeeConnection = new MarketplaceConnection();
        shopeeConnection.setId(10L);
        shopeeConnection.setTenantId(1L);
        shopeeConnection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        shopeeConnection.setShopId("727720655");
        shopeeConnection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        tiktokConnection = new MarketplaceConnection();
        tiktokConnection.setId(20L);
        tiktokConnection.setTenantId(1L);
        tiktokConnection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        tiktokConnection.setAppSecretEncrypted("enc_tiktok_secret");
        tiktokConnection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        controller = new WebhookController(
                repository,
                encryptionService,
                genericSignatureValidator,
                shopeeProperties,
                shopeeWebhookValidator,
                shopeeWebhookDispatcher,
                rabbitTemplate,
                objectMapper
        );
    }

    @Test
    @DisplayName("Shopee Webhook: returns 200 OK with success envelope when signature and timestamp are valid")
    void receiveWebhook_Shopee_Valid_Returns200() {
        when(repository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(shopeeConnection));
        when(shopeeWebhookValidator.isValidSignature(any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(shopeeWebhookValidator.isTimestampValid(anyLong()))
                .thenReturn(true);

        String payload = "{\"code\":3,\"shop_id\":727720655,\"timestamp\":1714000500,\"data\":{\"ordersn\":\"SP01\",\"status\":\"READY_TO_SHIP\"}}";

        ResponseEntity<String> response = controller.receiveWebhook(
                1L,
                "shopee",
                "valid-auth-header-sig",
                null,
                request,
                payload
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"error\":\"\""));
        assertTrue(response.getBody().contains("\"message\":\"success\""));

        verify(shopeeWebhookDispatcher).dispatch(eq(1L), any(ShopeeWebhookEvent.class), eq(payload));
    }

    @Test
    @DisplayName("Shopee Webhook: returns 401 when signature is invalid")
    void receiveWebhook_Shopee_InvalidSignature_Returns401() {
        when(repository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(shopeeConnection));
        when(shopeeWebhookValidator.isValidSignature(any(), any(), any(), any(), any()))
                .thenReturn(false);

        ResponseEntity<String> response = controller.receiveWebhook(
                1L,
                "shopee",
                "bad-sig",
                null,
                request,
                "{\"code\":3}"
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("INVALID_SIGNATURE"));
        verifyNoInteractions(shopeeWebhookDispatcher);
    }

    @Test
    @DisplayName("Shopee Webhook: returns 401 when timestamp tolerance is exceeded")
    void receiveWebhook_Shopee_ExpiredTimestamp_Returns401() {
        when(repository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(shopeeConnection));
        when(shopeeWebhookValidator.isValidSignature(any(), any(), any(), any(), any()))
                .thenReturn(true);
        when(shopeeWebhookValidator.isTimestampValid(anyLong()))
                .thenReturn(false);

        String payload = "{\"code\":3,\"shop_id\":727720655,\"timestamp\":1600000000,\"data\":{}}";

        ResponseEntity<String> response = controller.receiveWebhook(
                1L,
                "shopee",
                "valid-sig",
                null,
                request,
                payload
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("EXPIRED_TIMESTAMP"));
        verifyNoInteractions(shopeeWebhookDispatcher);
    }

    @Test
    @DisplayName("Shopee Webhook: returns 400 when tenantId is <= 0")
    void receiveWebhook_InvalidTenant_Returns400() {
        ResponseEntity<String> response = controller.receiveWebhook(
                0L,
                "shopee",
                "sig",
                null,
                request,
                "{}"
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("INVALID_TENANT"));
    }

    @Test
    @DisplayName("Shopee Webhook: returns 400 when connection not found for tenant")
    void receiveWebhook_UnknownTenant_Returns400() {
        when(repository.findByTenantIdAndPlatform(999L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.receiveWebhook(
                999L,
                "shopee",
                "sig",
                null,
                request,
                "{}"
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("UNKNOWN_TENANT"));
    }

    @Test
    @DisplayName("Shopee Webhook: returns 400 when platform name is invalid")
    void receiveWebhook_InvalidPlatform_Returns400() {
        ResponseEntity<String> response = controller.receiveWebhook(
                1L,
                "invalid_platform",
                "sig",
                null,
                request,
                "{}"
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("UNKNOWN_PLATFORM"));
    }

    @Test
    @DisplayName("Generic Webhook (TikTok): forwards to RabbitMQ on valid signature")
    void receiveWebhook_TikTok_Valid_Returns200() {
        when(repository.findByTenantIdAndPlatform(1L, MarketplaceConnection.Platform.TIKTOK_SHOP))
                .thenReturn(Optional.of(tiktokConnection));
        when(encryptionService.decrypt("enc_tiktok_secret")).thenReturn("plain_secret");
        when(genericSignatureValidator.isValid(anyString(), anyString(), anyString())).thenReturn(true);

        String payload = "{\"event\":\"order_created\"}";

        ResponseEntity<String> response = controller.receiveWebhook(
                1L,
                "tiktok_shop",
                null,
                "valid-tiktok-sig",
                request,
                payload
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OK", response.getBody());
        verify(rabbitTemplate).convertAndSend(anyString(), eq("order.incoming"), eq(payload));
    }
}
