package com.smartomni.integration.webhook.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles business processing and RabbitMQ dispatching for Shopee Push Webhooks (UC-32, FR-060).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopeeWebhookDispatcher {

    public enum DispatchOutcome {
        HANDLED_AUTH,
        HANDLED_DEAUTH,
        ENQUEUED_ORDER,
        ENQUEUED_TRACKING,
        HANDLED_PROMOTION,
        ENQUEUED_RETURN,
        IGNORED
    }

    private final MarketplaceConnectionRepository connectionRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ShopeeWebhookDispatcher(MarketplaceConnectionRepository connectionRepository,
                                   RabbitTemplate rabbitTemplate,
                                   ObjectMapper objectMapper) {
        this(connectionRepository, rabbitTemplate, objectMapper, Clock.systemUTC());
    }

    @Transactional
    public DispatchOutcome dispatch(Long tenantId, ShopeeWebhookEvent event, String rawPayload) {
        int code = event.getCode();
        log.info("Dispatching Shopee webhook push: code={}, tenantId={}, shopId={}",
                code, tenantId, event.getShopId());

        switch (code) {
            case 1: // shop_authorization_push
                handleAuthorizationPush(tenantId, event);
                return DispatchOutcome.HANDLED_AUTH;

            case 2:  // shop_deauthorization_push
            case 16: // shop_authorization_canceled_push
                handleDeauthorizationPush(tenantId, event);
                return DispatchOutcome.HANDLED_DEAUTH;

            case 3: // order_status_push
                enqueueOrderEvent(tenantId, event, rawPayload);
                return DispatchOutcome.ENQUEUED_ORDER;

            case 4: // order_tracking_no_push
                enqueueTrackingEvent(tenantId, event);
                return DispatchOutcome.ENQUEUED_TRACKING;

            case 15: // item_promotion_push
                log.info("Shopee item promotion update received for shopId={}: data={}",
                        event.getShopId(), event.getData());
                return DispatchOutcome.HANDLED_PROMOTION;

            case 29: // return_push / return_updates_push
                enqueueReturnEvent(tenantId, event);
                return DispatchOutcome.ENQUEUED_RETURN;

            default:
                log.info("Shopee push code {} acknowledged without special action", code);
                return DispatchOutcome.IGNORED;
        }
    }

    private void handleAuthorizationPush(Long tenantId, ShopeeWebhookEvent event) {
        try (var scope = TenantContext.openScope(tenantId, null, null)) {
            connectionRepository.findByTenantIdAndPlatform(tenantId, MarketplaceConnection.Platform.SHOPEE)
                    .ifPresent(conn -> {
                        if (conn.getStatus() != MarketplaceConnection.ConnectionStatus.CONNECTED) {
                            conn.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
                        }
                        conn.setLastErrorCode(null);
                        conn.setLastErrorAt(null);
                        connectionRepository.save(conn);
                        log.info("Shopee connection re-authorized for tenant={}, shopId={}", tenantId, event.getShopId());
                    });
        }
    }

    private void handleDeauthorizationPush(Long tenantId, ShopeeWebhookEvent event) {
        try (var scope = TenantContext.openScope(tenantId, null, null)) {
            connectionRepository.findByTenantIdAndPlatform(tenantId, MarketplaceConnection.Platform.SHOPEE)
                    .ifPresent(conn -> {
                        conn.setStatus(MarketplaceConnection.ConnectionStatus.REVOKED);
                        conn.setLastErrorCode("SHOP_DEAUTHORIZED");
                        conn.setLastErrorAt(clock.instant());
                        conn.setAccessTokenEncrypted(null);
                        conn.setRefreshTokenEncrypted(null);
                        connectionRepository.save(conn);
                        log.warn("Shopee connection marked REVOKED for tenant={}, shopId={}", tenantId, event.getShopId());
                    });
        }
    }

    private void enqueueOrderEvent(Long tenantId, ShopeeWebhookEvent event, String rawPayload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("tenantId", tenantId);
        message.put("platform", "SHOPEE");
        message.put("platformOrderId", event.orderSn());
        message.put("status", event.orderStatus());
        message.put("shopId", event.getShopId() != null ? String.valueOf(event.getShopId()) : null);
        message.put("timestamp", event.getTimestamp());
        message.put("source", "WEBHOOK");
        message.put("rawPayload", rawPayload);

        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, "order.incoming", message);
        log.info("Published Shopee order push to RabbitMQ: ordersn={}, status={}",
                event.orderSn(), event.orderStatus());
    }

    private void enqueueTrackingEvent(Long tenantId, ShopeeWebhookEvent event) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("tenantId", tenantId);
        message.put("platform", "SHOPEE");
        message.put("platformOrderId", event.orderSn());
        message.put("trackingNo", event.trackingNo());
        message.put("shopId", event.getShopId() != null ? String.valueOf(event.getShopId()) : null);
        message.put("timestamp", event.getTimestamp());

        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, "order.tracking", message);
        log.info("Published Shopee tracking push to RabbitMQ: ordersn={}, trackingNo={}",
                event.orderSn(), event.trackingNo());
    }

    private void enqueueReturnEvent(Long tenantId, ShopeeWebhookEvent event) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("tenantId", tenantId);
        message.put("platform", "SHOPEE");
        message.put("platformOrderId", event.orderSn());
        message.put("returnSn", event.returnSn());
        message.put("shopId", event.getShopId() != null ? String.valueOf(event.getShopId()) : null);
        message.put("timestamp", event.getTimestamp());

        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, "return.incoming", message);
        log.info("Published Shopee return push to RabbitMQ: returnSn={}, ordersn={}",
                event.returnSn(), event.orderSn());
    }
}
