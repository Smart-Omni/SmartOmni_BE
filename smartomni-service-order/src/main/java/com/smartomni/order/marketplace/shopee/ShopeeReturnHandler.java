package com.smartomni.order.marketplace.shopee;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.ReturnRefund;
import com.smartomni.order.entity.ReturnRefundItem;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.ReturnRefundItemRepository;
import com.smartomni.order.repository.ReturnRefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Shopee return push (code 29 → {@code ShopeeReturnSyncService} → {@code /internal/orders/returns}), plan 4.1.
 * <p>
 * Source: {@code shopee-simulator} @ 4ce7c84, {@code simulator.router.ts} ~3480/3595 and {@code returns.router.ts}
 * ~270/410: a return (full or partial) moves the order COMPLETED → TO_RETURN, and a cancelled/closed return moves it
 * back to COMPLETED. These changes are sent only as push 29, never as push 3.
 * <p>
 * So this handler only records the return in {@code return_refund} and its items. It never changes the order status
 * and never restocks: RETURNED and the restock belong to WF-05 inspection ({@code ReturnRefundService}). Once WF-05
 * has inspected or restocked the return, a later push only refreshes the marketplace status.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopeeReturnHandler {

    private final OrderRepository orderRepository;
    private final ReturnRefundRepository returnRefundRepository;
    private final ReturnRefundItemRepository returnRefundItemRepository;

    public Order handle(Long tenantId, Order order, ProcessReturnRequest request) {
        if (request.getReturnReason() != null && !request.getReturnReason().isBlank()) {
            order.setReturnReason(request.getReturnReason());
        }
        if (request.getRejectReason() != null && !request.getRejectReason().isBlank()) {
            order.setRejectReason(request.getRejectReason());
        }
        if (request.getEvidenceUrls() != null && !request.getEvidenceUrls().isEmpty()) {
            order.setEvidenceUrls(String.join(",", request.getEvidenceUrls()));
        }

        log.info("Shopee return {} for order {} ({}): action={}, returnStatus={}, partial={}; order status stays {}",
                request.getReturnId(), order.getPlatformOrderId(), order.getId(), request.getReturnAction(),
                request.getReturnStatus(), request.getPartialReturn(), order.getStatus());
        upsertReturnRecord(tenantId, order, request);
        return orderRepository.save(order);
    }

    /** Coarse refund status and inspection placeholder for a return that WF-05 has not inspected yet. */
    static ReturnRefund.RefundStatus refundStatus(ProcessReturnRequest request) {
        ProcessReturnRequest.ReturnAction action = request.getReturnAction();
        if (action == null) {
            return ReturnRefund.RefundStatus.REQUESTED;
        }
        return switch (action) {
            case AWAITING_INSPECTION, DISPUTE_CLAIM -> ReturnRefund.RefundStatus.REQUESTED;
            case RECEIVE_APPROVE, DISPUTE_APPROVED -> ReturnRefund.RefundStatus.APPROVED;
            case REFUND_ONLY -> ReturnRefund.RefundStatus.COMPLETED;
            // ShopeeReturnSyncService sends RECEIVE_REJECT for a CANCELLED/CLOSED return: the record is closed and the
            // order stays COMPLETED, as the simulator moves it back to COMPLETED.
            case RECEIVE_REJECT, RECEIVE_REJECTED -> ReturnRefund.RefundStatus.REJECTED;
        };
    }

    private static ReturnRefund.InspectionResultType inspectionPlaceholder(ProcessReturnRequest request) {
        ProcessReturnRequest.ReturnAction action = request.getReturnAction();
        if (action == ProcessReturnRequest.ReturnAction.REFUND_ONLY) {
            return ReturnRefund.InspectionResultType.MATCHED; // nothing comes back to inspect
        }
        return ReturnRefund.InspectionResultType.PENDING;
    }

    private void upsertReturnRecord(Long tenantId, Order order, ProcessReturnRequest request) {
        if (request.getReturnId() == null) {
            return;
        }

        Long connectionId = request.getConnectionId() != null ? request.getConnectionId() : order.getConnectionId();
        ReturnRefund refund = null;
        if (connectionId != null) {
            refund = returnRefundRepository.findByTenantIdAndConnectionIdAndExternalReturnId(tenantId, connectionId, request.getReturnId())
                    .orElse(null);
        }
        if (refund == null) {
            refund = returnRefundRepository.findByTenantIdAndOrderId(tenantId, order.getId()).orElse(null);
        }
        if (refund == null) {
            refund = new ReturnRefund();
            refund.setTenantId(tenantId);
            refund.setOrderId(order.getId());
            refund.setConnectionId(connectionId);
            refund.setExternalReturnId(request.getReturnId());
            refund.setPlatform(order.getPlatform());
        }

        refund.setMarketplaceStatus(request.getReturnStatus());
        if (request.getRefundAmount() != null) {
            refund.setRefundAmount(request.getRefundAmount());
        }
        if (request.getRefundSubtotal() != null) {
            refund.setRefundSubtotal(request.getRefundSubtotal());
        }
        if (request.getRefundShippingFee() != null) {
            refund.setRefundShippingFee(request.getRefundShippingFee());
        }
        if (request.getReverseShippingFee() != null) {
            refund.setReverseShippingFee(request.getReverseShippingFee());
        }
        Instant created = com.smartomni.order.marketplace.OrderStatusFlow.platformInstant(request.getPlatformCreateTime());
        if (created != null && refund.getMarketplaceCreatedAt() == null) {
            refund.setMarketplaceCreatedAt(created);
        }
        boolean inspectedByWarehouse = refund.isRestocked() || refund.getReceivedAt() != null
                || order.getStatus() == Order.OrderStatus.RETURNED;
        if (!inspectedByWarehouse) {
            refund.setReturnType(request.getReturnType());
            refund.setReason(request.getReturnReason());
            refund.setStatus(refundStatus(request));
            refund.setInspectionResult(inspectionPlaceholder(request));
            if (request.getEvidenceUrls() != null && !request.getEvidenceUrls().isEmpty()) {
                refund.setVideoEvidenceUrl(String.join(",", request.getEvidenceUrls()));
            }
        } else {
            log.info("Shopee return {} already inspected by WF-05; only the marketplace status is refreshed", request.getReturnId());
        }
        ReturnRefund savedRefund = returnRefundRepository.save(refund);
        Long refundId = savedRefund != null && savedRefund.getId() != null ? savedRefund.getId() : refund.getId();

        if (refundId != null && request.getItems() != null && !request.getItems().isEmpty()) {
            List<ReturnRefundItem> existingItems = returnRefundItemRepository.findByTenantIdAndReturnRefundId(tenantId, refundId);
            if (existingItems.isEmpty()) {
                for (ProcessReturnRequest.ReturnItemDto itemDto : request.getItems()) {
                    ReturnRefundItem item = new ReturnRefundItem();
                    item.setTenantId(tenantId);
                    item.setReturnRefundId(refundId);
                    item.setSkuId(itemDto.getSkuId());
                    item.setExternalItemId(itemDto.getExternalItemId());
                    item.setItemName(itemDto.getName());
                    item.setQuantityReturned(itemDto.getQuantity() != null ? itemDto.getQuantity() : 1);
                    item.setQuantityInspected(0);
                    item.setRefundAmount(itemDto.getRefundAmount());
                    returnRefundItemRepository.save(item);
                }
            }
        }
    }
}
