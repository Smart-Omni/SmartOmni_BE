package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.AesEncryptionService;
import com.smartomni.integration.service.WebhookSignatureValidator;
import com.smartomni.integration.webhook.shopee.ShopeeWebhookDispatcher;
import com.smartomni.integration.webhook.shopee.ShopeeWebhookEvent;
import com.smartomni.integration.webhook.shopee.ShopeeWebhookValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * UC-32: Dong bo don hang qua Webhook (Real-time Sync).
 * FR-057: Nginx route theo path; controller xac thuc tenant_id tu webhook path.
 * FR-058: Xac thuc chu ky HMAC/SHA256.
 * FR-059: Phan hoi 200 OK trong 1-3 giay - KHONG xu ly dong bo ngay.
 * FR-060: Day message vao RabbitMQ kem tenant_id.
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private static final String ORDER_EXCHANGE = RabbitMQConfig.ORDER_EXCHANGE;
    private static final String ORDER_ROUTING_KEY = "order.incoming";

    private final MarketplaceConnectionRepository connectionRepository;
    private final AesEncryptionService encryptionService;
    private final WebhookSignatureValidator signatureValidator;
    private final ShopeeProperties shopeeProperties;
    private final ShopeeWebhookValidator shopeeWebhookValidator;
    private final ShopeeWebhookDispatcher shopeeWebhookDispatcher;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @PostMapping("/{tenantId}/{platform}")
    public ResponseEntity<String> receiveWebhook(@PathVariable Long tenantId,
                                                 @PathVariable String platform,
                                                 @RequestHeader(value = "Authorization", required = false) String authHeader,
                                                 @RequestHeader(value = "X-Signature", required = false) String xSignature,
                                                 HttpServletRequest request,
                                                 @RequestBody String rawPayload) {

        if (tenantId == null || tenantId <= 0) {
            return ResponseEntity.badRequest().body("{\"error\":\"INVALID_TENANT\",\"message\":\"Invalid tenant\"}");
        }

        MarketplaceConnection.Platform platformEnum;
        try {
            platformEnum = MarketplaceConnection.Platform.valueOf(platform.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Webhook rejected: unknown platform '{}'", platform);
            return ResponseEntity.badRequest().body("{\"error\":\"UNKNOWN_PLATFORM\",\"message\":\"Unknown platform\"}");
        }

        MarketplaceConnection connection;
        try (var scope = TenantContext.openScope(tenantId, null, null)) {
            connection = connectionRepository.findByTenantIdAndPlatform(tenantId, platformEnum).orElse(null);
        }

        if (connection == null) {
            log.warn("Webhook received for tenant={} but no connection found for {}", tenantId, platform);
            return ResponseEntity.badRequest().body("{\"error\":\"UNKNOWN_TENANT\",\"message\":\"Unknown tenant/platform\"}");
        }

        // Platform-specific signature verification and dispatch
        if (platformEnum == MarketplaceConnection.Platform.SHOPEE) {
            return handleShopeeWebhook(tenantId, connection, authHeader, xSignature, request, rawPayload);
        } else {
            return handleGenericWebhook(tenantId, connection, authHeader, xSignature, rawPayload);
        }
    }

    private ResponseEntity<String> handleShopeeWebhook(Long tenantId,
                                                       MarketplaceConnection connection,
                                                       String authHeader,
                                                       String xSignature,
                                                       HttpServletRequest request,
                                                       String rawPayload) {
        String signature = (authHeader != null && !authHeader.isBlank()) ? authHeader : xSignature;

        String secretKey = null;
        if (connection.getAppSecretEncrypted() != null && !connection.getAppSecretEncrypted().isBlank()) {
            secretKey = encryptionService.decrypt(connection.getAppSecretEncrypted());
        }
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = shopeeProperties.getPartnerKey();
        }

        String requestUrl = request != null ? request.getRequestURL().toString() : null;
        String requestUri = request != null ? request.getRequestURI() : null;

        if (!shopeeWebhookValidator.isValidSignature(signature, requestUrl, requestUri, rawPayload, secretKey)) {
            log.warn("Invalid Shopee webhook signature for tenant={}, shopId={}", tenantId, connection.getShopId());
            return ResponseEntity.status(401).body("{\"error\":\"INVALID_SIGNATURE\",\"message\":\"Invalid signature\"}");
        }

        ShopeeWebhookEvent event;
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            event = ShopeeWebhookEvent.builder()
                    .code(root.path("code").asInt(0))
                    .shopId(root.hasNonNull("shop_id") ? root.path("shop_id").asLong() : null)
                    .partnerId(root.hasNonNull("partner_id") ? root.path("partner_id").asLong() : null)
                    .timestamp(root.hasNonNull("timestamp") ? root.path("timestamp").asLong() : null)
                    .data(root.path("data"))
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse Shopee webhook payload for tenant={}: {}", tenantId, e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"INVALID_JSON\",\"message\":\"Invalid JSON payload\"}");
        }

        if (!shopeeWebhookValidator.isTimestampValid(event.getTimestamp())) {
            log.warn("Shopee webhook timestamp expired/skewed for tenant={}", tenantId);
            return ResponseEntity.status(401).body("{\"error\":\"EXPIRED_TIMESTAMP\",\"message\":\"Timestamp expired or replay detected\"}");
        }

        // FR-059: Asynchronous handoff to RabbitMQ / DB without blocking response
        try {
            shopeeWebhookDispatcher.dispatch(tenantId, event, rawPayload);
        } catch (Exception ex) {
            log.error("Error dispatching Shopee webhook push code {}: {}", event.getCode(), ex.getMessage(), ex);
        }

        // Fast 200 OK standard Shopee response
        return ResponseEntity.ok("{\"error\":\"\",\"message\":\"success\"}");
    }

    private ResponseEntity<String> handleGenericWebhook(Long tenantId,
                                                        MarketplaceConnection connection,
                                                        String authHeader,
                                                        String xSignature,
                                                        String rawPayload) {
        String signature = (xSignature != null && !xSignature.isBlank()) ? xSignature : authHeader;
        String appSecret = encryptionService.decrypt(connection.getAppSecretEncrypted());

        if (signature == null || !signatureValidator.isValid(rawPayload, signature, appSecret)) {
            log.warn("Generic webhook signature invalid for tenant={}", tenantId);
            return ResponseEntity.status(401).body("{\"error\":\"INVALID_SIGNATURE\",\"message\":\"Invalid signature\"}");
        }

        rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_ROUTING_KEY, rawPayload);
        return ResponseEntity.ok("OK");
    }
}
