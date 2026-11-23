package com.smartomni.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.security.WebhookHmacValidator;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.dto.tiktok.TiktokWebhookType;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.AesEncryptionService;
import com.smartomni.integration.service.tiktok.TiktokFulfillmentService;
import com.smartomni.integration.service.tiktok.TiktokOrderIngestionService;
import com.smartomni.integration.service.tiktok.TiktokReturnService;
import com.smartomni.integration.service.tiktok.TiktokWebhookEventRecorder;
import com.smartomni.integration.service.tiktok.TiktokWebhookEventRecorder.Claim;
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
import java.util.Optional;

/**
 * Controller tiếp nhận Webhook từ TikTok Shop Open Platform.
 * Endpoint: POST /api/v1/integrations/tiktok/webhooks (không cần JWT, xác thực bằng chữ ký HMAC).
 *
 * Nhiệm vụ:
 * 1. Đọc header signature (Authorization / X-Tts-Signature) & Timestamp.
 * 2. Phân giải shop_id -> tenant_id tương ứng qua MarketplaceConnectionRepository.
 * 3. Kiểm tra HMAC-SHA256 và Timestamp tolerance (5 phút) qua WebhookHmacValidator.
 * 4. Idempotency theo tts_notification_id ({@link TiktokWebhookEventRecorder}): event đã xử lý trả 200 và dừng.
 * 5. Route theo {@link TiktokWebhookType}; ORDER_STATUS_CHANGE lấy chi tiết đơn rồi chuẩn hóa như polling.
 *
 * Lỗi tạm thời trả 503 để TikTok gửi lại. Event đó chưa được đánh dấu đã xử lý, nên lần gửi lại vẫn được xử lý.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/integrations/tiktok")
@RequiredArgsConstructor
public class TiktokWebhookController {

    public static final String ORDER_EXCHANGE = "smartomni.order.exchange";
    public static final String ORDER_ROUTING_KEY = "order.incoming";

    private static final Map<String, Object> SUCCESS = Map.of("code", 0, "message", "success");

    private final MarketplaceConnectionRepository connectionRepository;
    private final TiktokWebhookEventRecorder eventRecorder;
    private final WebhookHmacValidator webhookHmacValidator;
    private final TiktokProperties properties;
    private final AesEncryptionService encryptionService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TiktokOrderIngestionService orderIngestionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TiktokFulfillmentService fulfillmentService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TiktokReturnService returnService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.smartomni.integration.service.tiktok.TiktokConnectionService connectionService;

    public void setConnectionService(com.smartomni.integration.service.tiktok.TiktokConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    public void setReturnService(TiktokReturnService returnService) {
        this.returnService = returnService;
    }

    public void setFulfillmentService(TiktokFulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

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
        Optional<TiktokWebhookType> type = TiktokWebhookType.fromCode(payload.getType());
        boolean deauthorization = isDeauthorizationEvent(type, rawPayload);
        String eventType = deauthorization ? TiktokWebhookType.SELLER_DEAUTHORIZATION.topic()
                : TiktokWebhookType.logName(payload.getType());

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
            eventRecorder.recordRejected(tenantId, eventType, rawPayload);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature or timestamp skew exceeded");
        }

        // 5. Idempotency theo tts_notification_id. Postgres cho phép nhiều NULL trong unique nên id là bắt buộc.
        String eventId = payload.getTtsNotificationId();
        if (eventId == null || eventId.isBlank()) {
            log.warn("TikTok Webhook thiếu tts_notification_id: shop_id={}, tenant_id={}", shopId, tenantId);
            return ResponseEntity.badRequest().body("Missing tts_notification_id");
        }
        Claim claim = eventRecorder.claim(tenantId, eventId, eventType, rawPayload);
        if (claim == Claim.DUPLICATE) {
            log.info("TikTok Webhook trùng tts_notification_id={} (tenant={}), bỏ qua", eventId, tenantId);
            return ResponseEntity.ok(SUCCESS);
        }

        // 6. Route theo loại sự kiện
        ResponseEntity<?> response = deauthorization
                ? handleDeauthorization(tenantId, connection)
                : route(type.orElse(null), payload, rawPayload, tenantId, connection);

        if (response.getStatusCode().is2xxSuccessful()) {
            eventRecorder.markProcessed(tenantId, eventId, eventType);
        }
        return response;
    }

    private ResponseEntity<?> route(TiktokWebhookType type, TiktokWebhookPayload payload, String rawPayload,
                                    Long tenantId, MarketplaceConnection connection) {
        if (type == null) {
            log.info("TikTok Webhook type={} chưa hỗ trợ, chỉ ghi log (tenant={})", payload.getType(), tenantId);
            return ResponseEntity.ok(SUCCESS);
        }
        switch (type) {
            case ORDER_STATUS_CHANGE:
                return handleOrderStatusChange(tenantId, connection, payload);
            case INVENTORY_CHANGED_BY_SHOP:
                try {
                    rabbitTemplate.convertAndSend(ORDER_EXCHANGE, RabbitMQConfig.INVENTORY_ROUTING_KEY,
                            Map.of("tenantId", tenantId, "connectionId", connection.getId(), "payload", rawPayload));
                    return ResponseEntity.ok(SUCCESS);
                } catch (Exception ex) {
                    log.error("Lỗi khi xếp hàng webhook tồn TikTok: {}", ex.getMessage(), ex);
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Inventory webhook queue unavailable");
                }
            case PACKAGE_UPDATE:
                if (fulfillmentService != null) {
                    try {
                        fulfillmentService.handlePackageStatusChange(tenantId, payload);
                    } catch (Exception ex) {
                        log.error("Lỗi khi xử lý PACKAGE_UPDATE webhook: {}", ex.getMessage(), ex);
                    }
                }
                return ResponseEntity.ok(SUCCESS);
            case REVERSE_STATUS_UPDATE:
                if (returnService != null) {
                    try {
                        returnService.handleReturnWebhook(tenantId, payload);
                    } catch (Exception ex) {
                        log.error("Lỗi khi xử lý REVERSE_STATUS_UPDATE webhook: {}", ex.getMessage(), ex);
                    }
                }
                return ResponseEntity.ok(SUCCESS);
            case CANCELLATION_STATUS_CHANGE:
            case RETURN_STATUS_CHANGE:
                // Đã nhận và ghi log; xử lý trạng thái trả/hủy thuộc phase 2.
                log.info("TikTok Webhook {} đã nhận (tenant={}), chưa xử lý ở phase này", type, tenantId);
                return ResponseEntity.ok(SUCCESS);
            default:
                log.info("TikTok Webhook {} chỉ ghi log (tenant={})", type, tenantId);
                return ResponseEntity.ok(SUCCESS);
        }
    }

    /**
     * ORDER_STATUS_CHANGE chỉ mang order_id: lấy chi tiết đơn qua Get Order Detail rồi đưa vào luồng ingest chung.
     * Lỗi tạm thời (mạng, 5xx, hàng đợi) trả 503 để TikTok gửi lại.
     */
    private ResponseEntity<?> handleOrderStatusChange(Long tenantId, MarketplaceConnection connection, TiktokWebhookPayload payload) {
        try (var scope = TenantContext.openScope(tenantId, "order_polling_worker", null)) {
            orderIngestionService.processOrderEvent(tenantId, connection, payload.getData());
            return ResponseEntity.ok(SUCCESS);
        } catch (TiktokApiException ex) {
            if (ex.isTransientFailure()) {
                log.warn("TikTok tạm thời lỗi khi lấy chi tiết đơn (tenant={}): {}", tenantId, ex.summary());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("TikTok temporarily unavailable");
            }
            // TikTok từ chối (ví dụ đơn không tồn tại): gửi lại cũng không khác, nên xác nhận đã nhận.
            log.error("TikTok từ chối lấy chi tiết đơn (tenant={}): {} {}", tenantId, ex.getMessage(), ex.summary());
            return ResponseEntity.ok(SUCCESS);
        } catch (BusinessException ex) {
            if (ex.getHttpStatus().is4xxClientError()) {
                log.warn("ORDER_STATUS_CHANGE không hợp lệ (tenant={}): {}", tenantId, ex.getMessage());
                return ResponseEntity.badRequest().body(ex.getMessage());
            }
            log.error("Không xử lý được ORDER_STATUS_CHANGE (tenant={}): {}", tenantId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Order event could not be processed");
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý ORDER_STATUS_CHANGE (tenant={}): {}", tenantId, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Order event could not be processed");
        }
    }

    private ResponseEntity<?> handleDeauthorization(Long tenantId, MarketplaceConnection connection) {
        log.warn("TikTok Webhook SELLER_DEAUTHORIZATION received: connection={}, tenant_id={}", connection.getId(), tenantId);
        if (connectionService != null) {
            try {
                connectionService.markRevoked(tenantId, connection.getId());
            } catch (Exception ex) {
                log.error("Lỗi khi gọi markRevoked qua connectionService: {}", ex.getMessage(), ex);
            }
        } else {
            try (var scope = TenantContext.openScope(tenantId, null, null)) {
                connection.setStatus(MarketplaceConnection.ConnectionStatus.REVOKED);
                connection.setLastErrorCode("REVOKED");
                connection.setLastErrorAt(Instant.now());
                connection.setAccessTokenEncrypted(null);
                connection.setRefreshTokenEncrypted(null);
                connectionRepository.save(connection);
            }
        }
        return ResponseEntity.ok(SUCCESS);
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

    /**
     * Type 6 ({@code SELLER_DEAUTHORIZATION}). For backward compatibility a type this enum does not know is still
     * treated as deauthorization when its payload names such an event; known types are never matched by text.
     */
    private static boolean isDeauthorizationEvent(Optional<TiktokWebhookType> type, String rawPayload) {
        if (type.isPresent()) {
            return type.get() == TiktokWebhookType.SELLER_DEAUTHORIZATION;
        }
        if (rawPayload == null) {
            return false;
        }
        String upper = rawPayload.toUpperCase();
        return upper.contains("EVENT_AUTHORIZED_CANCEL")
                || upper.contains("DEAUTHORIZATION")
                || upper.contains("SHOP_DEAUTHORIZED")
                || upper.contains("AUTHORIZED_CANCEL");
    }
}
