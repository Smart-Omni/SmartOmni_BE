package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.security.WebhookHmacValidator;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.WebhookEventLog;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.repository.WebhookEventLogRepository;
import com.smartomni.integration.service.AesEncryptionService;
import com.smartomni.integration.service.tiktok.TiktokFulfillmentService;
import com.smartomni.integration.service.tiktok.TiktokReturnService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Controller tiếp nhận Webhook từ TikTok Shop Open Platform.
 * Endpoint: POST /api/v1/integrations/tiktok/webhooks
 *
 * Nhiệm vụ:
 * 1. Đọc header signature (Authorization / X-Tts-Signature) & Timestamp.
 * 2. Phân giải shop_id -> tenant_id tương ứng qua MarketplaceConnectionRepository.
 * 3. Kiểm tra tính hợp lệ của HMAC-SHA256 signature và Timestamp tolerance (5 phút) qua WebhookHmacValidator.
 * 4. Ghi log Audit Webhook vào bảng webhook_events_log.
 * 5. Đẩy message vào RabbitMQ exchange cho Order Service xử lý bất đồng bộ.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/integrations/tiktok")
@RequiredArgsConstructor
public class TiktokWebhookController {

    public static final String ORDER_EXCHANGE = "smartomni.order.exchange";
    public static final String ORDER_ROUTING_KEY = "order.incoming";

    private final MarketplaceConnectionRepository connectionRepository;
    private final WebhookEventLogRepository webhookEventLogRepository;
    private final WebhookHmacValidator webhookHmacValidator;
    private final TiktokProperties properties;
    private final AesEncryptionService encryptionService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TiktokFulfillmentService fulfillmentService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TiktokReturnService returnService;

    @PostMapping("/webhooks")
    public ResponseEntity<?> receiveWebhook(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestHeader(value = "X-Tts-Signature", required = false) String xTtsSignatureHeader,
            @RequestHeader(value = "Timestamp", required = false) String timestampHeader,
            @RequestHeader(value = "X-Tts-Timestamp", required = false) String xTtsTimestampHeader,
            @RequestBody String rawPayload,
            HttpServletRequest request) {

        // 1. Đọc Header Signature
        String signature = (authorizationHeader != null && !authorizationHeader.isBlank())
                ? authorizationHeader.trim()
                : (xTtsSignatureHeader != null ? xTtsSignatureHeader.trim() : null);

        if (signature == null || signature.isBlank()) {
            log.warn("TikTok Webhook bị từ chối: Thiếu signature header (Authorization / X-Tts-Signature)");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing signature");
        }

        // Parse Payload
        TiktokWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawPayload, TiktokWebhookPayload.class);
        } catch (Exception e) {
            log.error("TikTok Webhook payload không hợp lệ: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid JSON payload");
        }

        // 2. Đọc & chuẩn hóa Timestamp
        long timestamp = resolveTimestamp(timestampHeader, xTtsTimestampHeader, payload.getTimestamp());
        if (timestamp <= 0) {
            log.warn("TikTok Webhook bị từ chối: Thiếu hoặc sai định dạng timestamp");
            return ResponseEntity.badRequest().body("Missing or invalid timestamp");
        }

        // 3. Bóc tách shop_id và tra cứu connection / tenant_id
        String shopId = payload.getShopId();
        if (shopId == null || shopId.isBlank()) {
            log.warn("TikTok Webhook thiếu shop_id trong body");
            return ResponseEntity.badRequest().body("Missing shop_id in payload");
        }

        MarketplaceConnection connection = connectionRepository.findFirstByShopCipher(shopId)
                .or(() -> connectionRepository.findFirstByShopId(shopId))
                .or(() -> connectionRepository.findFirstBySellerOpenId(shopId))
                .orElse(null);

        if (connection == null) {
            log.warn("TikTok Webhook nhận shop_id={} nhưng không tìm thấy kết nối trong hệ thống", shopId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Unknown shop_id: " + shopId);
        }

        Long tenantId = connection.getTenantId();

        // 4. Lấy app_secret để kiểm tra chữ ký HMAC-SHA256
        String appSecret = properties.getAppSecret();
        if ((appSecret == null || appSecret.isBlank()) && connection.getAppSecretEncrypted() != null) {
            appSecret = encryptionService.decrypt(connection.getAppSecretEncrypted());
        }

        String requestPath = request != null ? request.getRequestURI() : "/api/v1/integrations/tiktok/webhooks";
        boolean isValidSignature = webhookHmacValidator.validateTikTokWebhook(
                appSecret, requestPath, timestamp, rawPayload, signature);

        if (!isValidSignature) {
            log.warn("TikTok Webhook chữ ký không hợp lệ hoặc lệch timestamp quá 5 phút: shop_id={}, tenant_id={}",
                    shopId, tenantId);
            // Ghi audit log với trạng thái signature_valid = false
            saveAuditLog(tenantId, payload, rawPayload, false, false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature or timestamp skew exceeded");
        }

        // 5. Ghi log Audit Webhook (webhook_events_log) trạng thái hợp lệ & đã xếp hàng
        saveAuditLog(tenantId, payload, rawPayload, true, true);

        // 6. Xử lý chuyên biệt theo event type: PACKAGE_STATUS_CHANGE hoặc RETURN_STATUS_CHANGE
        if (payload.getType() != null && payload.getType() == 3 && fulfillmentService != null) {
            try {
                fulfillmentService.handlePackageStatusChange(tenantId, payload);
            } catch (Exception ex) {
                log.error("Lỗi khi xử lý PACKAGE_STATUS_CHANGE webhook: {}", ex.getMessage(), ex);
            }
        } else if (payload.getType() != null && (payload.getType() == 2 || payload.getType() == 4) && returnService != null) {
            try {
                returnService.handleReturnWebhook(tenantId, payload);
            } catch (Exception ex) {
                log.error("Lỗi khi xử lý RETURN_STATUS_CHANGE webhook: {}", ex.getMessage(), ex);
            }
        }

        // 7. Đẩy message vào RabbitMQ để Order Service xử lý bất đồng bộ
        try {
            rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_ROUTING_KEY, rawPayload);
        } catch (Exception ex) {
            log.error("Lỗi khi đẩy message webhook vào RabbitMQ: {}", ex.getMessage(), ex);
        }

        // 8. Phản hồi nhanh 200 OK cho TikTok Shop
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }

    private long resolveTimestamp(String timestampHeader, String xTtsTimestampHeader, Long bodyTimestamp) {
        if (timestampHeader != null && !timestampHeader.isBlank()) {
            try {
                return Long.parseLong(timestampHeader.trim());
            } catch (NumberFormatException ignored) {}
        }
        if (xTtsTimestampHeader != null && !xTtsTimestampHeader.isBlank()) {
            try {
                return Long.parseLong(xTtsTimestampHeader.trim());
            } catch (NumberFormatException ignored) {}
        }
        return bodyTimestamp != null ? bodyTimestamp : 0L;
    }

    private void saveAuditLog(Long tenantId, TiktokWebhookPayload payload, String rawPayload, boolean signatureValid, boolean queued) {
        try (var scope = TenantContext.openScope(tenantId, null, null)) {
            WebhookEventLog eventLog = new WebhookEventLog();
            eventLog.setTenantId(tenantId);
            eventLog.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
            eventLog.setEventId(payload.getTtsNotificationId());
            eventLog.setEventType(resolveEventType(payload.getType()));
            eventLog.setSignatureValid(signatureValid);
            eventLog.setRawPayload(rawPayload);
            eventLog.setQueued(queued);
            eventLog.setProcessedAt(queued ? Instant.now() : null);
            eventLog.setReceivedAt(Instant.now());
            webhookEventLogRepository.save(eventLog);
        } catch (Exception e) {
            log.warn("Không thể lưu webhook audit log cho eventId={}: {}", payload.getTtsNotificationId(), e.getMessage());
        }
    }

    private String resolveEventType(Integer type) {
        if (type == null) {
            return "UNKNOWN";
        }
        return switch (type) {
            case 1 -> "ORDER_STATUS_CHANGE";
            case 2 -> "REVERSE_STATUS_CHANGE";
            case 3 -> "PACKAGE_STATUS_CHANGE";
            default -> "TIKTOK_EVENT_TYPE_" + type;
        };
    }
}
