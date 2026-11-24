package com.smartomni.order.marketplace.tiktok;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.order.client.InventoryServiceClient;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.ReturnRefund;
import com.smartomni.order.entity.ReturnRefundItem;
import com.smartomni.order.marketplace.OrderStatusFlow;
import com.smartomni.order.repository.ReturnRefundItemRepository;
import com.smartomni.order.repository.ReturnRefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TikTok return, following the simulator's SmartOmni requirements (phase 07b). Moved unchanged from
 * {@code OrderService.handleTiktokReturn}.
 * <ul>
 *   <li>the order status is never changed by a return (07b.2: the platform has no RETURNED order status);</li>
 *   <li>the request keeps the marketplace {@code return_status} and {@code update_time}; an event older than the
 *       stored {@code update_time} is ignored (07b.2);</li>
 *   <li>when the request reaches {@code RETURN_OR_REFUND_REQUEST_SUCCESS} or {@code _COMPLETE}, every returned
 *       line is restocked through an idempotent receipt keyed by (order, order line item), so a webhook replay or
 *       a polling overlap cannot restock twice (07b.4). The simulator never adds stock back itself.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokReturnHandler {

    /** Return statuses after which the returned units are restocked (simulator requirements 07b.4). */
    static final Set<String> TIKTOK_RETURN_DONE = Set.of(
            "RETURN_OR_REFUND_REQUEST_SUCCESS", "RETURN_OR_REFUND_REQUEST_COMPLETE");

    private final ReturnRefundRepository returnRefundRepository;
    private final ReturnRefundItemRepository returnRefundItemRepository;
    private final InventoryServiceClient inventoryServiceClient;

    public Order handle(Long tenantId, Order order, ProcessReturnRequest request) {
        if (request.getReturnId() == null || request.getReturnId().isBlank()) {
            log.warn("TikTok return without return id for order {}", order.getPlatformOrderId());
            return order;
        }
        Long connectionId = request.getConnectionId() != null ? request.getConnectionId() : order.getConnectionId();
        ReturnRefund refund = (connectionId != null
                ? returnRefundRepository.findByTenantIdAndConnectionIdAndExternalReturnId(tenantId, connectionId, request.getReturnId())
                : Optional.<ReturnRefund>empty())
                .orElseGet(() -> {
                    ReturnRefund created = new ReturnRefund();
                    created.setTenantId(tenantId);
                    created.setOrderId(order.getId());
                    created.setConnectionId(connectionId);
                    created.setExternalReturnId(request.getReturnId());
                    created.setPlatform(order.getPlatform());
                    return created;
                });

        Instant incoming = OrderStatusFlow.platformInstant(request.getPlatformUpdateTime());
        if (incoming != null && refund.getMarketplaceUpdatedAt() != null && incoming.isBefore(refund.getMarketplaceUpdatedAt())) {
            log.info("Bỏ qua trạng thái trả hàng cũ cho return {}: {} < {}", request.getReturnId(), incoming,
                    refund.getMarketplaceUpdatedAt());
            return order;
        }

        String marketplaceStatus = request.getReturnStatus();
        refund.setMarketplaceStatus(marketplaceStatus);
        if (incoming != null) {
            refund.setMarketplaceUpdatedAt(incoming);
        }
        refund.setReturnType(request.getReturnType());
        if (request.getReturnReason() != null) {
            refund.setReason(request.getReturnReason());
        }
        if (request.getRefundAmount() != null) {
            refund.setRefundAmount(request.getRefundAmount());
        }
        refund.setStatus(refundStatus(marketplaceStatus));
        refund = returnRefundRepository.save(refund);

        List<ReturnRefundItem> items = upsertReturnItems(tenantId, refund, request);

        if (marketplaceStatus != null && TIKTOK_RETURN_DONE.contains(marketplaceStatus)) {
            boolean allRestocked = !items.isEmpty();
            for (ReturnRefundItem item : items) {
                if (item.getSkuId() == null || item.getExternalItemId() == null) {
                    allRestocked = false;
                    log.warn("Không hoàn kho dòng trả hàng chưa ánh xạ SKU: return={}, line={}",
                            request.getReturnId(), item.getExternalItemId());
                    continue;
                }
                int qty = item.getQuantityReturned() != null && item.getQuantityReturned() > 0 ? item.getQuantityReturned() : 1;
                inventoryServiceClient.restock(item.getSkuId(), qty,
                        "tiktok-return:" + order.getPlatformOrderId() + ":" + item.getExternalItemId());
            }
            refund.setRestocked(allRestocked);
            returnRefundRepository.save(refund);
        }
        return order;
    }

    /** Coarse SmartOmni refund status; the exact platform status is kept in marketplace_status. */
    static ReturnRefund.RefundStatus refundStatus(String marketplaceStatus) {
        if (marketplaceStatus == null) {
            return ReturnRefund.RefundStatus.REQUESTED;
        }
        return switch (marketplaceStatus) {
            case "RETURN_OR_REFUND_REQUEST_SUCCESS", "RETURN_OR_REFUND_REQUEST_COMPLETE" -> ReturnRefund.RefundStatus.COMPLETED;
            case "AWAITING_BUYER_SHIP", "BUYER_SHIPPED_ITEM" -> ReturnRefund.RefundStatus.APPROVED;
            case "REFUND_OR_RETURN_REQUEST_REJECT", "REJECT_RECEIVE_PACKAGE", "RETURN_OR_REFUND_REQUEST_CANCEL" ->
                    ReturnRefund.RefundStatus.REJECTED;
            default -> ReturnRefund.RefundStatus.REQUESTED;
        };
    }

    /** One item per order line item of the request (TikTok lists one line per unit); refreshes the SKU mapping. */
    private List<ReturnRefundItem> upsertReturnItems(Long tenantId, ReturnRefund refund, ProcessReturnRequest request) {
        List<ReturnRefundItem> existing = new ArrayList<>(
                returnRefundItemRepository.findByTenantIdAndReturnRefundId(tenantId, refund.getId()));
        if (request.getItems() == null) {
            return existing;
        }
        for (ProcessReturnRequest.ReturnItemDto dto : request.getItems()) {
            ReturnRefundItem item = existing.stream()
                    .filter(e -> e.getExternalItemId() != null && e.getExternalItemId().equals(dto.getExternalItemId()))
                    .findFirst().orElse(null);
            if (item == null) {
                item = new ReturnRefundItem();
                item.setTenantId(tenantId);
                item.setReturnRefundId(refund.getId());
                item.setExternalItemId(dto.getExternalItemId());
                item.setQuantityInspected(0);
                existing.add(item);
            }
            if (dto.getSkuId() != null) {
                item.setSkuId(dto.getSkuId());
            }
            item.setItemName(dto.getName());
            item.setQuantityReturned(dto.getQuantity() != null ? dto.getQuantity() : 1);
            item.setRefundAmount(dto.getRefundAmount());
            returnRefundItemRepository.save(item);
        }
        return existing;
    }
}
