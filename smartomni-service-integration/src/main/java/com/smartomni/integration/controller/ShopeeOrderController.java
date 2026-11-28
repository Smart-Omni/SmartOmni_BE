package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.shopee.ShopeeOrderClient;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.ShopeeOrderDetail;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.order.ShopeeOrderSyncService;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller exposing Shopee order operations, sync triggers, and cancellation flows (UC-10, UC-32, UC-34).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/marketplace-connections/shopee/orders")
@RequiredArgsConstructor
public class ShopeeOrderController {

    private final ShopeeTenantConnections tenantConnections;
    private final ShopeeOrderClient orderClient;
    private final ShopeeOrderSyncService orderSyncService;

    public record ShopeeOrderPageResponse(
            List<ShopeeOrderDetail> orders,
            boolean more,
            String nextCursor,
            Integer totalCount
    ) {}

    /**
     * Lists orders directly from Shopee Simulator.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ShopeeOrderDetail>>> listOrders(
            @RequestParam(required = false) Long timeFrom,
            @RequestParam(required = false) Long timeTo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String orderStatus) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        var listResult = orderClient.getOrderList(connection.getId(), timeFrom, timeTo, pageSize, cursor, orderStatus);

        if (listResult.orders().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }

        List<String> orderSns = listResult.orders().stream()
                .map(ShopeeOrderClient.OrderSummary::orderSn)
                .toList();

        List<ShopeeOrderDetail> details = orderClient.getOrderDetail(connection.getId(), orderSns);
        return ResponseEntity.ok(ApiResponse.success(details));
    }

    /**
     * Lists a single cursor-paginated page of orders from Shopee Simulator with pagination metadata.
     */
    @GetMapping("/page")
    public ResponseEntity<ApiResponse<ShopeeOrderPageResponse>> listOrdersPage(
            @RequestParam(required = false) Long timeFrom,
            @RequestParam(required = false) Long timeTo,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String orderStatus) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        var listResult = orderClient.getOrderList(connection.getId(), timeFrom, timeTo, pageSize, cursor, orderStatus);

        if (listResult.orders().isEmpty()) {
            Integer total = (cursor == null || cursor.isBlank()) ? 0 : null;
            return ResponseEntity.ok(ApiResponse.success(new ShopeeOrderPageResponse(List.of(), false, "", total)));
        }

        List<String> orderSns = listResult.orders().stream()
                .map(ShopeeOrderClient.OrderSummary::orderSn)
                .toList();

        List<ShopeeOrderDetail> details = orderClient.getOrderDetail(connection.getId(), orderSns);
        String nextCursor = listResult.more() && listResult.nextCursor() != null ? listResult.nextCursor() : "";
        Integer totalCount = computeTotalCount(connection.getId(), timeFrom, timeTo, pageSize, cursor, orderStatus, listResult);
        return ResponseEntity.ok(ApiResponse.success(new ShopeeOrderPageResponse(details, listResult.more(), nextCursor, totalCount)));
    }

    private Integer computeTotalCount(
            Long connectionId,
            Long timeFrom,
            Long timeTo,
            int pageSize,
            String initialCursor,
            String orderStatus,
            ShopeeOrderClient.OrderListResult firstPage) {
        if (initialCursor != null && !initialCursor.isBlank()) {
            return null;
        }
        int total = firstPage.orders().size();
        if (!firstPage.more() || firstPage.nextCursor() == null || firstPage.nextCursor().isBlank()) {
            return total;
        }
        String walkCursor = firstPage.nextCursor();
        java.util.Set<String> seenCursors = new java.util.HashSet<>();
        seenCursors.add(walkCursor);
        for (int page = 1; page < 20; page++) {
            var nextPage = orderClient.getOrderList(connectionId, timeFrom, timeTo, pageSize, walkCursor, orderStatus);
            total += nextPage.orders().size();
            String next = nextPage.nextCursor();
            if (!nextPage.more() || next == null || next.isBlank() || !seenCursors.add(next)) {
                break;
            }
            walkCursor = next;
        }
        return total;
    }

    /**
     * Ingests and synchronizes a single order by order SN into SmartOmni.
     */
    @PostMapping("/{orderSn}/sync")
    public ResponseEntity<ApiResponse<WebhookOrderPayload>> syncSingleOrder(@PathVariable String orderSn) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        WebhookOrderPayload payload = orderSyncService.syncOrder(connection, orderSn);
        return ResponseEntity.ok(ApiResponse.success("Order synced successfully", payload));
    }

    /**
     * Triggers batch order synchronization from Shopee Simulator.
     */
    @PostMapping("/batch-sync")
    public ResponseEntity<ApiResponse<List<WebhookOrderPayload>>> batchSyncOrders(
            @RequestParam(required = false) Long timeFrom,
            @RequestParam(required = false) Long timeTo,
            @RequestParam(required = false) String orderStatus,
            @RequestParam(defaultValue = "50") int pageSize) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        List<WebhookOrderPayload> synced = orderSyncService.syncOrdersBatch(connection, timeFrom, timeTo, orderStatus, pageSize);
        return ResponseEntity.ok(ApiResponse.success("Batch sync completed", synced));
    }

    /**
     * Cancels an order on Shopee.
     */
    @PostMapping("/{orderSn}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelOrder(
            @PathVariable String orderSn,
            @RequestBody(required = false) Map<String, String> body) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        String reason = body != null ? body.get("cancelReason") : "OUT_OF_STOCK";
        boolean success = orderSyncService.cancelOrder(connection, orderSn, reason);
        return ResponseEntity.ok(ApiResponse.success("Order cancellation submitted", Map.of("orderSn", orderSn, "canceled", success)));
    }

    /**
     * Accepts or rejects a buyer cancellation request.
     */
    @PostMapping("/{orderSn}/handle-cancellation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBuyerCancellation(
            @PathVariable String orderSn,
            @RequestBody Map<String, String> body) {

        MarketplaceConnection connection = tenantConnections.requireConnected();
        String operation = body != null ? body.get("operation") : "ACCEPT";
        boolean success = orderSyncService.handleBuyerCancellation(connection, orderSn, operation);
        return ResponseEntity.ok(ApiResponse.success("Buyer cancellation handled", Map.of("orderSn", orderSn, "success", success)));
    }

    /**
     * Marks a PROCESSED order as handed over to the carrier on Shopee Simulator.
     */
    @PostMapping("/{orderSn}/handover")
    public ResponseEntity<ApiResponse<Map<String, Object>>> handoverOrder(@PathVariable String orderSn) {
        MarketplaceConnection connection = tenantConnections.requireConnected();
        boolean success = orderClient.handoverOrder(connection.getId(), orderSn);
        return ResponseEntity.ok(ApiResponse.success("Order handed over to carrier", Map.of("orderSn", orderSn, "handedOver", success)));
    }
}
