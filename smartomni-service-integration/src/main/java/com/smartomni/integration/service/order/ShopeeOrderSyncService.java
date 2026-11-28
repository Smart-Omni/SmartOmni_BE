package com.smartomni.integration.service.order;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.shopee.ShopeeOrderClient;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderListResult;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderSummary;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.ShopeeOrderDetail;
import com.smartomni.integration.config.RabbitMQConfig;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service to orchestrate order synchronization and lifecycle transitions with Shopee Open API v2.
 */
@Slf4j
@Service
public class ShopeeOrderSyncService {

    private final ShopeeOrderClient orderClient;
    private final ShopeeOrderMapper orderMapper;
    private final RabbitTemplate rabbitTemplate;
    private final com.smartomni.integration.service.finance.MarketplaceFinanceSyncService financeSyncService;

    public ShopeeOrderSyncService(ShopeeOrderClient orderClient, ShopeeOrderMapper orderMapper, RabbitTemplate rabbitTemplate) {
        this(orderClient, orderMapper, rabbitTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ShopeeOrderSyncService(ShopeeOrderClient orderClient, ShopeeOrderMapper orderMapper, RabbitTemplate rabbitTemplate,
                                  com.smartomni.integration.service.finance.MarketplaceFinanceSyncService financeSyncService) {
        this.orderClient = orderClient;
        this.orderMapper = orderMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.financeSyncService = financeSyncService;
    }

    /**
     * Ingests and synchronizes a single order by order SN, then publishes to RabbitMQ.
     */
    public WebhookOrderPayload syncOrder(MarketplaceConnection connection, String orderSn) {
        log.info("Syncing Shopee order {} for tenant {}", orderSn, connection.getTenantId());

        List<ShopeeOrderDetail> details = orderClient.getOrderDetail(connection.getId(), List.of(orderSn));
        if (details.isEmpty()) {
            throw new BusinessException("Shopee order not found: " + orderSn, HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        }

        ShopeeOrderDetail detail = details.get(0);
        WebhookOrderPayload payload = orderMapper.toPayload(connection.getTenantId(), connection.getId(), detail);

        publishOrderToQueue(payload);
        if (financeSyncService != null) {
            financeSyncService.syncShopeeOrderFinance(connection, detail.orderSn(), detail.orderStatus());
        }
        return payload;
    }

    /**
     * Ingests all pages of orders by time range / status, useful for initial sync or fallback polling.
     */
    public List<WebhookOrderPayload> syncOrdersBatch(MarketplaceConnection connection, Long timeFrom, Long timeTo,
                                                    String orderStatus, int pageSize) {
        log.info("Batch syncing Shopee orders for tenant {}, status={}, from={}, to={}",
                connection.getTenantId(), orderStatus, timeFrom, timeTo);

        List<WebhookOrderPayload> synced = new ArrayList<>();
        java.util.Set<String> seenCursors = new java.util.HashSet<>();
        String cursor = null;
        final int maxPages = 20;

        for (int page = 0; page < maxPages; page++) {
            OrderListResult listResult = orderClient.getOrderList(
                    connection.getId(), timeFrom, timeTo, pageSize, cursor, orderStatus
            );

            if (listResult == null || listResult.orders() == null || listResult.orders().isEmpty()) {
                break;
            }

            List<String> orderSnList = listResult.orders().stream()
                    .map(OrderSummary::orderSn)
                    .toList();

            List<ShopeeOrderDetail> details = orderClient.getOrderDetail(connection.getId(), orderSnList);
            for (ShopeeOrderDetail detail : details) {
                try {
                    WebhookOrderPayload payload = orderMapper.toPayload(connection.getTenantId(), connection.getId(), detail);
                    publishOrderToQueue(payload);
                    if (financeSyncService != null) {
                        financeSyncService.syncShopeeOrderFinance(connection, detail.orderSn(), detail.orderStatus());
                    }
                    synced.add(payload);
                } catch (Exception ex) {
                    log.error("Failed to process Shopee order {}: {}", detail.orderSn(), ex.getMessage());
                }
            }

            String next = listResult.nextCursor();
            if (!listResult.more() || next == null || next.isBlank() || !seenCursors.add(next)) {
                break;
            }
            cursor = next;
        }

        return synced;
    }

    /**
     * Requests cancellation of an order on Shopee.
     */
    public boolean cancelOrder(MarketplaceConnection connection, String orderSn, String cancelReason) {
        log.info("Canceling Shopee order {} for tenant {}: reason={}", orderSn, connection.getTenantId(), cancelReason);
        return orderClient.cancelOrder(connection.getId(), orderSn, cancelReason);
    }

    /**
     * Accepts or rejects a buyer cancellation request.
     */
    public boolean handleBuyerCancellation(MarketplaceConnection connection, String orderSn, String operation) {
        log.info("Handling buyer cancellation for Shopee order {}: operation={}", orderSn, operation);
        return orderClient.handleBuyerCancellation(connection.getId(), orderSn, operation);
    }

    private void publishOrderToQueue(WebhookOrderPayload payload) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, "order.incoming", payload);
        log.info("Published order {} (status={}) to RabbitMQ exchange {}",
                payload.getPlatformOrderId(), payload.getOrderStatus(), RabbitMQConfig.ORDER_EXCHANGE);
    }
}
