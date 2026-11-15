package com.smartomni.order.service;

import com.smartomni.common.dto.ReturnDetailDto;
import com.smartomni.common.dto.ReturnInspectionRequest;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.client.InventoryServiceClient;
import com.smartomni.order.dto.CreateReturnRequest;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderItem;
import com.smartomni.order.entity.OrderStatusHistory;
import com.smartomni.order.entity.ReturnRefund;
import com.smartomni.order.entity.ReturnRefundItem;
import com.smartomni.order.repository.OrderItemRepository;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.OrderStatusHistoryRepository;
import com.smartomni.order.repository.ReturnRefundItemRepository;
import com.smartomni.order.repository.ReturnRefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service managing Returns & Refunds lifecycle, staff inspection, and safe restock state machine (UC-13, FR-030, WF-05, DS-09).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnRefundService {

    private final ReturnRefundRepository returnRefundRepository;
    private final ReturnRefundItemRepository returnRefundItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final InventoryServiceClient inventoryServiceClient;

    @Transactional(readOnly = true)
    public Page<ReturnDetailDto> getReturns(Pageable pageable) {
        Long tenantId = requireTenantId();
        return returnRefundRepository.findByTenantId(tenantId, pageable)
                .map(this::mapToReturnDetailDto);
    }

    @Transactional(readOnly = true)
    public ReturnDetailDto getReturnDetail(Long returnId) {
        Long tenantId = requireTenantId();
        ReturnRefund refund = returnRefundRepository.findByIdAndTenantId(returnId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Yeu cau hoan tra khong ton tai: " + returnId));
        return mapToReturnDetailDto(refund);
    }

    @Transactional
    public ReturnRefund createReturnRequest(CreateReturnRequest request) {
        Long tenantId = requireTenantId();
        Order order = orderRepository.findByIdAndTenantId(request.getOrderId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Don hang khong ton tai: " + request.getOrderId()));

        ReturnRefund refund = new ReturnRefund();
        refund.setTenantId(tenantId);
        refund.setOrderId(order.getId());
        refund.setPlatform(order.getPlatform());
        refund.setConnectionId(order.getConnectionId());
        refund.setReason(request.getReason());
        refund.setStatus(ReturnRefund.RefundStatus.REQUESTED);
        return returnRefundRepository.save(refund);
    }

    @Transactional
    public ReturnDetailDto performInspection(Long returnId, ReturnInspectionRequest request) {
        Long tenantId = requireTenantId();
        ReturnRefund refund = returnRefundRepository.findByIdAndTenantId(returnId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Yeu cau hoan tra khong ton tai: " + returnId));
        Order order = orderRepository.findByIdAndTenantId(refund.getOrderId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Don hang khong ton tai: " + refund.getOrderId()));

        if (request == null || request.getResult() == null) {
            throw new IllegalArgumentException("Inspection result must not be null");
        }

        log.info("Performing return inspection: tenantId={}, returnId={}, orderId={}, result={}",
                tenantId, returnId, order.getId(), request.getResult());

        if (request.getResult() == ReturnInspectionRequest.InspectionResult.MATCHED) {
            refund.setInspectionResult(ReturnRefund.InspectionResultType.MATCHED);
            refund.setReceivedAt(Instant.now());
            if (request.getVideoEvidenceUrl() != null && !request.getVideoEvidenceUrl().isBlank()) {
                refund.setVideoEvidenceUrl(request.getVideoEvidenceUrl());
            }
            refund.setStatus(ReturnRefund.RefundStatus.COMPLETED);

            // Restock items with idempotency key
            List<ReturnRefundItem> returnItems = returnRefundItemRepository.findByTenantIdAndReturnRefundId(tenantId, refund.getId());
            if (!returnItems.isEmpty()) {
                for (ReturnRefundItem item : returnItems) {
                    if (item.getSkuId() != null && item.getQuantityReturned() != null && item.getQuantityReturned() > 0) {
                        int qty = (item.getQuantityInspected() != null && item.getQuantityInspected() > 0)
                                ? item.getQuantityInspected() : item.getQuantityReturned();
                        String idempotencyKey = String.format("%d:%s:%s:%d:RESTOCK",
                                tenantId,
                                refund.getConnectionId() != null ? refund.getConnectionId() : 0L,
                                refund.getExternalReturnId() != null ? refund.getExternalReturnId() : refund.getId(),
                                item.getSkuId());
                        inventoryServiceClient.restock(item.getSkuId(), qty, idempotencyKey);
                        log.info("Restocked SKU {} qty {} with idempotency key {}", item.getSkuId(), qty, idempotencyKey);
                    }
                }
            } else {
                for (OrderItem item : orderItemRepository.findByOrderIdAndTenantId(refund.getOrderId(), tenantId)) {
                    if (item.getSkuId() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                        String idempotencyKey = String.format("%d:%s:%s:%d:RESTOCK",
                                tenantId,
                                refund.getConnectionId() != null ? refund.getConnectionId() : 0L,
                                refund.getExternalReturnId() != null ? refund.getExternalReturnId() : refund.getId(),
                                item.getSkuId());
                        inventoryServiceClient.restock(item.getSkuId(), item.getQuantity(), idempotencyKey);
                        log.info("Restocked order item SKU {} qty {} with idempotency key {}", item.getSkuId(), item.getQuantity(), idempotencyKey);
                    }
                }
            }
            refund.setRestocked(true);
            order.setStatus(Order.OrderStatus.RETURNED);
            order.setRestockStatus("RESTOCKED");
        } else {
            ReturnRefund.InspectionResultType mappedResult = switch (request.getResult()) {
                case DAMAGED -> ReturnRefund.InspectionResultType.DAMAGED;
                case SWAPPED -> ReturnRefund.InspectionResultType.SWAPPED;
                case MISSING_ITEMS -> ReturnRefund.InspectionResultType.MISSING_ITEMS;
                default -> ReturnRefund.InspectionResultType.DAMAGED;
            };
            refund.setInspectionResult(mappedResult);
            if (request.getVideoEvidenceUrl() != null && !request.getVideoEvidenceUrl().isBlank()) {
                refund.setVideoEvidenceUrl(request.getVideoEvidenceUrl());
            }
            refund.setStatus(ReturnRefund.RefundStatus.REJECTED);
            refund.setRestocked(false);
            order.setStatus(Order.OrderStatus.ON_HOLD);
            order.setRestockStatus("INSPECTION_REJECTED");
            log.warn("Return inspection rejected: returnId={}, result={}. Inventory will not be restocked.",
                    returnId, mappedResult);
        }

        returnRefundRepository.save(refund);
        orderRepository.save(order);

        OrderStatusHistory history = new OrderStatusHistory();
        history.setTenantId(tenantId);
        history.setOrderId(order.getId());
        history.setStatus(order.getStatus());
        history.setSource(OrderStatusHistory.OrderChangeSource.MANAGER);
        orderStatusHistoryRepository.save(history);

        return mapToReturnDetailDto(refund);
    }

    @Transactional
    public ReturnRefund approveAndRestock(Long refundId) {
        performInspection(refundId, ReturnInspectionRequest.builder()
                .result(ReturnInspectionRequest.InspectionResult.MATCHED)
                .note("Approved via legacy approveAndRestock endpoint")
                .build());
        return returnRefundRepository.findByIdAndTenantId(refundId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Yeu cau hoan/huy khong ton tai: " + refundId));
    }

    private ReturnDetailDto mapToReturnDetailDto(ReturnRefund refund) {
        Order order = orderRepository.findByIdAndTenantId(refund.getOrderId(), refund.getTenantId()).orElse(null);
        List<ReturnRefundItem> items = returnRefundItemRepository.findByTenantIdAndReturnRefundId(refund.getTenantId(), refund.getId());

        List<ReturnDetailDto.ReturnDetailItemDto> itemDtos = items.stream()
                .map(i -> ReturnDetailDto.ReturnDetailItemDto.builder()
                        .id(i.getId())
                        .skuId(i.getSkuId())
                        .externalItemId(i.getExternalItemId())
                        .itemName(i.getItemName())
                        .quantityReturned(i.getQuantityReturned())
                        .quantityInspected(i.getQuantityInspected())
                        .refundAmount(i.getRefundAmount())
                        .build())
                .collect(Collectors.toList());

        return ReturnDetailDto.builder()
                .id(refund.getId())
                .tenantId(refund.getTenantId())
                .orderId(refund.getOrderId())
                .platformOrderId(order != null ? order.getPlatformOrderId() : null)
                .connectionId(refund.getConnectionId())
                .platform(refund.getPlatform() != null ? refund.getPlatform().name() : null)
                .externalReturnId(refund.getExternalReturnId())
                .returnType(refund.getReturnType())
                .reason(refund.getReason())
                .status(refund.getStatus() != null ? refund.getStatus().name() : null)
                .inspectionResult(refund.getInspectionResult() != null ? refund.getInspectionResult().name() : null)
                .restocked(refund.isRestocked())
                .returnTrackingNumber(refund.getReturnTrackingNumber())
                .videoEvidenceUrl(refund.getVideoEvidenceUrl())
                .receivedAt(refund.getReceivedAt())
                .disputeStatus(refund.getDisputeStatus() != null ? refund.getDisputeStatus().name() : null)
                .refundAmount(refund.getRefundAmount())
                .items(itemDtos)
                .build();
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ResourceNotFoundException("Khong tim thay tenant hien tai");
        }
        return tenantId;
    }
}
