package com.smartomni.order.service;

import com.smartomni.common.dto.CreateManualOrderRequest;
import com.smartomni.common.dto.OrderDetailDto;
import com.smartomni.common.dto.OrderItemDto;
import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.common.dto.UpdateOrderTrackingRequest;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.client.InventoryServiceClient;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderItem;
import com.smartomni.order.entity.OrderStatusHistory;
import com.smartomni.order.entity.ReturnRefund;
import com.smartomni.order.entity.ReturnRefundItem;
import com.smartomni.order.entity.SellerWalletLedger;
import com.smartomni.order.repository.OrderItemRepository;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.OrderStatusHistoryRepository;
import com.smartomni.order.repository.ReturnRefundItemRepository;
import com.smartomni.order.repository.ReturnRefundRepository;
import com.smartomni.order.repository.SellerWalletLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * UC-33: Xử lý bất đồng bộ đơn hàng (Async Order Processing) - FR-061,062
 * UC-10: Theo dõi & xử lý đơn hàng - FR-023,024,025
 * UC-06: Tra cứu đơn hàng chéo nền tảng (Khách hàng)
 * Giai đoạn 3 (Bước 3.1): Quản lý vòng đời đơn hàng, ghi lịch sử order_status_history,
 * hoàn tồn kho khi CANCELLED và ghi nhận doanh thu vào seller_wallet_ledger khi COMPLETED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final SellerWalletLedgerRepository sellerWalletLedgerRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final ReturnRefundRepository returnRefundRepository;
    private final ReturnRefundItemRepository returnRefundItemRepository;

    /**
     * Được gọi bởi RabbitMQ Consumer (OrderMessageConsumer) - nội dung chính của UC-33.
     * FR-025: kiểm tra idempotency trước khi tạo mới hoặc cập nhật trạng thái nếu đơn đã tồn tại.
     */
    @Transactional
    public void processIncomingOrder(WebhookOrderPayload payload, Order.OrderSource source) {
        Order.Platform platform = Order.Platform.valueOf(payload.getPlatform());

        Optional<Order> existing = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(
                payload.getTenantId(), platform, payload.getPlatformOrderId());

        Instant incomingUpdate = platformInstant(payload.getPlatformUpdateTime());

        if (existing.isPresent()) {
            Order order = existing.get();
            log.info("Đơn hàng {} đã tồn tại (tenant={}, source={})",
                    payload.getPlatformOrderId(), payload.getTenantId(), source);

            // Webhooks can arrive out of order and polling can replay old state: never move an order back.
            // Without a platform time on either side (e.g. Shopee) the comparison is skipped.
            if (incomingUpdate != null && order.getPlatformUpdatedAt() != null
                    && incomingUpdate.isBefore(order.getPlatformUpdatedAt())) {
                log.info("Bỏ qua cập nhật cũ cho đơn {}: platform update {} < {} (source={})",
                        payload.getPlatformOrderId(), incomingUpdate, order.getPlatformUpdatedAt(), source);
                return;
            }
            if (incomingUpdate != null) {
                order.setPlatformUpdatedAt(incomingUpdate);
            }
            if (payload.getReconciliationReason() != null) {
                order.setReconciliationReason(payload.getReconciliationReason());
            }

            if (payload.getOrderStatus() != null && !payload.getOrderStatus().isBlank()) {
                Order.OrderStatus incomingStatus = TiktokOrderStatusMapper.map(payload.getOrderStatus());
                if (incomingStatus != order.getStatus()) {
                    log.info("Cập nhật trạng thái đơn hàng {} từ {} sang {} (source={})",
                            payload.getPlatformOrderId(), order.getStatus(), incomingStatus, source);
                    updateOrderStatus(order, incomingStatus, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                }
            }
            return;
        }

        Order order = new Order();
        order.setTenantId(payload.getTenantId());
        order.setPlatform(platform);
        order.setPlatformOrderId(payload.getPlatformOrderId());
        order.setCustomerName(payload.getCustomerName());
        order.setCustomerPhone(payload.getCustomerPhone());
        order.setShippingAddress(payload.getShippingAddress());
        order.setTotalAmount(payload.getTotalAmount());
        order.setSource(source);
        order.setPlatformUpdatedAt(incomingUpdate);
        order.setReconciliationReason(payload.getReconciliationReason());

        Order.OrderStatus initialStatus = payload.getOrderStatus() != null && !payload.getOrderStatus().isBlank()
                ? TiktokOrderStatusMapper.map(payload.getOrderStatus())
                : Order.OrderStatus.TO_SHIP;
        order.setStatus(initialStatus);
        if (initialStatus == Order.OrderStatus.COMPLETED) {
            markCompletedIfNeeded(order);
        }

        order = orderRepository.save(order);
        recordStatusHistory(order, initialStatus, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK, null);
        if (initialStatus == Order.OrderStatus.COMPLETED) {
            recordWalletLedger(order);
        }

        if (payload.getItems() != null) {
            for (var itemPayload : payload.getItems()) {
                OrderItem item = new OrderItem();
                item.setTenantId(order.getTenantId());
                item.setOrderId(order.getId());
                item.setSkuId(itemPayload.getSkuId());
                item.setQuantity(itemPayload.getQuantity());
                item.setUnitPrice(itemPayload.getUnitPrice());
                item.setImageUrl(itemPayload.getImageUrl());
                orderItemRepository.save(item);

                // FR-027, FR-028: gọi Inventory Service trừ kho nếu đã map được internal skuId
                if (platform != Order.Platform.TIKTOK_SHOP && itemPayload.getSkuId() != null
                        && itemPayload.getQuantity() != null && itemPayload.getQuantity() > 0) {
                    inventoryServiceClient.deductStock(itemPayload.getSkuId(), itemPayload.getQuantity());
                }
            }
        }
    }

    /**
     * Cập nhật trạng thái đơn hàng (cho Staff hoặc Webhook).
     */
    @Transactional
    public Order updateStatus(Long orderId, Order.OrderStatus newStatus) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ResourceNotFoundException("Đơn hàng không tồn tại: " + orderId);
        }
        Order order = orderRepository.findByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: " + orderId));
        return updateOrderStatus(order, newStatus, OrderStatusHistory.OrderChangeSource.STAFF);
    }

    /**
     * Xử lý chuyển đổi trạng thái vòng đời đơn hàng:
     * 1. Cập nhật trạng thái trong orders.
     * 2. Ghi lịch sử vào order_status_history.
     * 3. Hoàn trả tồn kho nếu CANCELLED trước khi giao bưu tá.
     * 4. Ghi nhận doanh thu vào seller_wallet_ledger nếu COMPLETED.
     */
    @Transactional
    public Order updateOrderStatus(Order order, Order.OrderStatus newStatus, OrderStatusHistory.OrderChangeSource source) {
        Order.OrderStatus previousStatus = order.getStatus();
        if (previousStatus == newStatus) {
            return order;
        }

        if (source == OrderStatusHistory.OrderChangeSource.STAFF
                && (previousStatus != Order.OrderStatus.PENDING || newStatus != Order.OrderStatus.TO_SHIP)) {
            throw new BusinessException("Nhân viên chỉ được chuyển PENDING sang TO_SHIP",
                    HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION");
        }

        if (!TiktokOrderStatusMapper.isValidTransition(previousStatus, newStatus)) {
            throw new BusinessException("Chuyển trạng thái không hợp lệ từ " + previousStatus + " sang " + newStatus,
                    HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION");
        }

        order.setStatus(newStatus);
        if (newStatus == Order.OrderStatus.COMPLETED) {
            markCompletedIfNeeded(order);
        }
        order = orderRepository.save(order);

        // 1. Ghi nhận dòng lịch sử thay đổi trạng thái
        recordStatusHistory(order, newStatus, source, null);

        // 2. Nếu đơn chuyển sang CANCELLED trước khi giao bưu tá: hoàn trả tồn kho
        if (newStatus == Order.OrderStatus.CANCELLED && isBeforeCourierHandover(previousStatus, order)) {
            restockOrderItems(order);
        }

        // 3. Nếu đơn chuyển sang COMPLETED: ghi nhận doanh thu vào seller_wallet_ledger
        if (newStatus == Order.OrderStatus.COMPLETED) {
            recordWalletLedger(order);
        }

        return order;
    }

    /** Platform epoch seconds (or millis, as some create_time values are) to an Instant; null when absent. */
    private static Instant platformInstant(Long epoch) {
        if (epoch == null || epoch <= 0) {
            return null;
        }
        return epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
    }

    private void markCompletedIfNeeded(Order order) {
        if (order.getCompletedAt() == null) {
            Instant completedAt = Instant.now();
            order.setCompletedAt(completedAt);
            order.setEligibleForPointsAt(completedAt.plus(15, java.time.temporal.ChronoUnit.DAYS));
        } else if (order.getEligibleForPointsAt() == null) {
            order.setEligibleForPointsAt(order.getCompletedAt().plus(15, java.time.temporal.ChronoUnit.DAYS));
        }
    }

    /**
     * Xử lý sự kiện ORDER_STATUS_CHANGE từ sàn TMĐT theo platformOrderId.
     */
    @Transactional
    public Order handleOrderStatusChange(Long tenantId, String platform, String platformOrderId, String rawPlatformStatus) {
        Order.Platform p = Order.Platform.valueOf(platform.toUpperCase());
        Order order = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(tenantId, p, platformOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: platform=" + platform + ", id=" + platformOrderId));
        Order.OrderStatus newStatus = TiktokOrderStatusMapper.map(rawPlatformStatus);
        return updateOrderStatus(order, newStatus, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
    }

    /**
     * Cập nhật thông tin vận đơn (tracking_number, shipping_method, courier_scanned_at)
     * từ sự kiện PACKAGE_STATUS_CHANGE của sàn TMĐT (Bước 4.1).
     */
    @Transactional
    public Order updateOrderTracking(UpdateOrderTrackingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must not be null");
        }
        Long tenantId = requireRequestTenant(request.getTenantId());
        Order.Platform platform = request.getPlatform() != null && !request.getPlatform().isBlank()
                ? Order.Platform.valueOf(request.getPlatform().toUpperCase())
                : Order.Platform.TIKTOK_SHOP;

        java.util.List<String> orderIds = new java.util.ArrayList<>();
        if (request.getOrderIdList() != null && !request.getOrderIdList().isEmpty()) {
            orderIds.addAll(request.getOrderIdList());
        } else if (request.getPlatformOrderId() != null && !request.getPlatformOrderId().isBlank()) {
            orderIds.add(request.getPlatformOrderId());
        }

        if (orderIds.isEmpty()) {
            log.warn("Không tìm thấy platformOrderId trong request cập nhật tracking: {}", request);
            return null;
        }

        Order lastUpdatedOrder = null;
        for (String platformOrderId : orderIds) {
            Optional<Order> orderOpt;
            orderOpt = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(tenantId, platform, platformOrderId);

            if (orderOpt.isEmpty()) {
                log.warn("Không tìm thấy đơn hàng để cập nhật tracking: tenantId={}, platform={}, orderId={}",
                        tenantId, platform, platformOrderId);
                continue;
            }

            Order order = orderOpt.get();

            // 1. Cập nhật mã vận đơn trackingNumber
            if (request.getTrackingNumber() != null && !request.getTrackingNumber().isBlank()) {
                order.setTrackingNumber(request.getTrackingNumber());
            }

            // 2. Cập nhật đơn vị vận chuyển shippingMethod
            if (request.getShippingProvider() != null && !request.getShippingProvider().isBlank()) {
                order.setShippingMethod(request.getShippingProvider());
            }

            // 3. Cập nhật courierScannedAt & trạng thái khi package chuyển sang IN_TRANSIT / PICKED_UP
            String pkgStatus = request.getPackageStatus();
            boolean isHandover = pkgStatus != null && (
                    pkgStatus.equalsIgnoreCase("IN_TRANSIT")
                            || pkgStatus.equalsIgnoreCase("PICKED_UP")
                            || pkgStatus.equalsIgnoreCase("SHIPPED"));

            if (isHandover) {
                if (order.getCourierScannedAt() == null) {
                    order.setCourierScannedAt(request.getCourierScannedAt() != null
                            ? request.getCourierScannedAt()
                            : Instant.now());
                }
                // Nếu trạng thái hiện tại là TO_SHIP hoặc PENDING hoặc ON_HOLD -> chuyển sang SHIPPED
                if (order.getStatus() == Order.OrderStatus.TO_SHIP
                        || order.getStatus() == Order.OrderStatus.PENDING
                        || order.getStatus() == Order.OrderStatus.ON_HOLD) {
                    log.info("Đơn hàng {} đã được bưu tá quét (packageStatus={}) -> Cập nhật trạng thái SHIPPED",
                            order.getPlatformOrderId(), pkgStatus);
                    updateOrderStatus(order, Order.OrderStatus.SHIPPED, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                }
            }

            lastUpdatedOrder = orderRepository.save(order);
            log.info("Đã cập nhật thông tin vận đơn cho đơn hàng id={}, orderId={}, trackingNumber={}, courierScannedAt={}",
                    order.getId(), order.getPlatformOrderId(), order.getTrackingNumber(), order.getCourierScannedAt());
        }

        return lastUpdatedOrder;
    }

    /**
     * Xử lý yêu cầu Trả hàng / Hoàn tiền (Returns & Refund Management - Bước 4.2).
     * - Cập nhật trạng thái đơn hàng (nếu trả hàng thành công -> RETURNED, hoặc ON_HOLD nếu đang xử lý).
     * - Ghi nhận lịch sử vào order_status_history.
     * - Nếu đơn hàng hoàn trả kho thành công (restockRequired = true): Gọi InventoryServiceClient để nhập lại tồn kho.
     */
    @Transactional
    public Order handleOrderReturn(ProcessReturnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ProcessReturnRequest must not be null");
        }
        Long tenantId = requireRequestTenant(request.getTenantId());
        Order.Platform platform = request.getPlatform() != null && !request.getPlatform().isBlank()
                ? Order.Platform.valueOf(request.getPlatform().toUpperCase())
                : Order.Platform.TIKTOK_SHOP;

        String platformOrderId = request.getOrderId() != null && !request.getOrderId().isBlank()
                ? request.getOrderId()
                : request.getPlatformOrderId();

        if (platformOrderId == null || platformOrderId.isBlank()) {
            log.warn("Không tìm thấy platformOrderId trong ProcessReturnRequest: {}", request);
            return null;
        }

        Optional<Order> orderOpt;
        if (request.getConnectionId() != null) {
            orderOpt = orderRepository.findByTenantIdAndConnectionIdAndPlatformOrderId(tenantId, request.getConnectionId(), platformOrderId);
        } else {
            orderOpt = Optional.empty();
        }
        if (orderOpt.isEmpty()) {
            orderOpt = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(tenantId, platform, platformOrderId);
        }

        if (orderOpt.isEmpty()) {
            log.warn("Không tìm thấy đơn hàng cho yêu cầu trả hàng: tenantId={}, platform={}, orderId={}",
                    tenantId, platform, platformOrderId);
            return null;
        }

        Order order = orderOpt.get();
        if (order.getPlatform() == Order.Platform.TIKTOK_SHOP) {
            return handleTiktokReturn(tenantId, order, request);
        }
        if (Boolean.TRUE.equals(request.getPartialReturn())) {
            // A partial return must not move the whole order to ON_HOLD/RETURNED: only the return record changes.
            log.info("Trả hàng một phần cho orderId={}, returnId={}: giữ nguyên trạng thái đơn {}",
                    platformOrderId, request.getReturnId(), order.getStatus());
            ReturnRefund.RefundStatus refundStatus = partialRefundStatus(request);
            upsertReturnRecord(tenantId, order, request, refundStatus,
                    refundStatus == ReturnRefund.RefundStatus.COMPLETED ? ReturnRefund.InspectionResultType.MATCHED
                            : ReturnRefund.InspectionResultType.PENDING, false);
            return orderRepository.save(order);
        }
        ProcessReturnRequest.ReturnAction returnAction = request.getReturnAction();
        String returnStatus = request.getReturnStatus();
        boolean isRestockRequired = Boolean.TRUE.equals(request.getRestockRequired());

        // Cập nhật lý do và bằng chứng nếu có
        if (request.getReturnReason() != null && !request.getReturnReason().isBlank()) {
            order.setReturnReason(request.getReturnReason());
        }
        if (request.getRejectReason() != null && !request.getRejectReason().isBlank()) {
            order.setRejectReason(request.getRejectReason());
        }
        if (request.getEvidenceUrls() != null && !request.getEvidenceUrls().isEmpty()) {
            order.setEvidenceUrls(String.join(",", request.getEvidenceUrls()));
        }

        if (returnAction != null) {
            switch (returnAction) {
                case AWAITING_INSPECTION -> {
                    log.info("Xử lý yêu cầu trả hàng vật lý (AWAITING_INSPECTION) cho orderId={}, returnId={}. Chờ nhân viên kho kiểm định.",
                            platformOrderId, request.getReturnId());
                    updateOrderStatus(order, Order.OrderStatus.ON_HOLD, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    order.setRestockStatus("AWAITING_INSPECTION");
                    upsertReturnRecord(tenantId, order, request, ReturnRefund.RefundStatus.REQUESTED, ReturnRefund.InspectionResultType.PENDING, false);
                }
                case RECEIVE_APPROVE -> {
                    log.info("Xác nhận trả hàng thành công (RECEIVE_APPROVE) cho orderId={}, returnId={}",
                            platformOrderId, request.getReturnId());
                    updateOrderStatus(order, Order.OrderStatus.RETURNED, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    if (isRestockRequired && order.getPlatform() == Order.Platform.TIKTOK_SHOP) {
                        order.setRestockStatus("PLATFORM_SYNC");
                    } else if (isRestockRequired) {
                        if ("RESTOCKED".equals(order.getRestockStatus())) {
                            log.info("Đơn hàng id={} đã được hoàn kho trước đó (idempotent), bỏ qua restock.", order.getId());
                        } else {
                            log.info("Yêu cầu nhập lại tồn kho cho đơn hàng hoàn trả id={}", order.getId());
                            restockOrderItems(order, request.getItems());
                            order.setRestockStatus("RESTOCKED");
                        }
                    } else {
                        order.setRestockStatus("SKIPPED");
                    }
                    upsertReturnRecord(tenantId, order, request, ReturnRefund.RefundStatus.COMPLETED, ReturnRefund.InspectionResultType.MATCHED, "RESTOCKED".equals(order.getRestockStatus()));
                }
                case REFUND_ONLY -> {
                    log.info("Hoàn tiền không hoàn hàng vật lý (REFUND_ONLY) cho orderId={}, returnId={}. Nghiêm cấm hoàn kho.",
                            platformOrderId, request.getReturnId());
                    updateOrderStatus(order, Order.OrderStatus.RETURNED, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    order.setRestockStatus("NO_RESTOCK_REFUND_ONLY");
                    upsertReturnRecord(tenantId, order, request, ReturnRefund.RefundStatus.COMPLETED, ReturnRefund.InspectionResultType.MATCHED, false);
                }
                case RECEIVE_REJECT, RECEIVE_REJECTED -> {
                    log.warn("Từ chối nhận hàng hoàn / trả hàng (RECEIVE_REJECT) cho orderId={}, returnId={}, rejectReason={}. Nghiêm cấm hoàn kho.",
                            platformOrderId, request.getReturnId(), request.getRejectReason());
                    updateOrderStatus(order, Order.OrderStatus.ON_HOLD, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    order.setRestockStatus("REJECTED");
                    upsertReturnRecord(tenantId, order, request, ReturnRefund.RefundStatus.REJECTED, ReturnRefund.InspectionResultType.DAMAGED, false);
                }
                case DISPUTE_CLAIM, DISPUTE_APPROVED -> {
                    log.info("Xử lý khiếu nại sàn ({}) cho orderId={}, returnId={}. Nghiêm cấm hoàn kho.",
                            returnAction, platformOrderId, request.getReturnId());
                    Order.OrderStatus targetStatus = (returnAction == ProcessReturnRequest.ReturnAction.DISPUTE_APPROVED)
                            ? Order.OrderStatus.RETURNED
                            : Order.OrderStatus.ON_HOLD;
                    updateOrderStatus(order, targetStatus, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    order.setRestockStatus("DISPUTED");
                    upsertReturnRecord(tenantId, order, request,
                            returnAction == ProcessReturnRequest.ReturnAction.DISPUTE_APPROVED ? ReturnRefund.RefundStatus.APPROVED : ReturnRefund.RefundStatus.REQUESTED,
                            ReturnRefund.InspectionResultType.PENDING, false);
                }
            }
        } else {
            boolean isReturnCompleted = returnStatus != null && (
                    returnStatus.toUpperCase().contains("COMPLETE")
                            || returnStatus.toUpperCase().contains("SUCCESS")
                            || returnStatus.toUpperCase().contains("APPROVED")
                            || returnStatus.equalsIgnoreCase("RETURNED"));

            if (isReturnCompleted) {
                log.info("Xác nhận trả hàng thành công cho orderId={}, returnId={}. Chuyển trạng thái sang RETURNED",
                        platformOrderId, request.getReturnId());
                updateOrderStatus(order, Order.OrderStatus.RETURNED, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);

                if (isRestockRequired && !"REFUND_ONLY".equalsIgnoreCase(request.getReturnType())
                        && order.getPlatform() == Order.Platform.TIKTOK_SHOP) {
                    order.setRestockStatus("PLATFORM_SYNC");
                } else if (isRestockRequired && !"REFUND_ONLY".equalsIgnoreCase(request.getReturnType())) {
                    if ("RESTOCKED".equals(order.getRestockStatus())) {
                        log.info("Đơn hàng id={} đã được hoàn kho trước đó (idempotent), bỏ qua restock.", order.getId());
                    } else {
                        log.info("Yêu cầu nhập lại tồn kho cho đơn hàng hoàn trả id={}", order.getId());
                        restockOrderItems(order, request.getItems());
                        order.setRestockStatus("RESTOCKED");
                    }
                } else {
                    order.setRestockStatus("SKIPPED");
                }
            } else {
                log.info("Tiếp nhận yêu cầu trả hàng (đang xử lý): orderId={}, returnStatus={}", platformOrderId, returnStatus);
                if (order.getStatus() != Order.OrderStatus.RETURNED && order.getStatus() != Order.OrderStatus.CANCELLED) {
                    updateOrderStatus(order, Order.OrderStatus.ON_HOLD, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                }
            }
        }

        return orderRepository.save(order);
    }


    /** Return statuses after which the returned units are restocked (simulator requirements 07b.4). */
    static final java.util.Set<String> TIKTOK_RETURN_DONE = java.util.Set.of(
            "RETURN_OR_REFUND_REQUEST_SUCCESS", "RETURN_OR_REFUND_REQUEST_COMPLETE");

    /**
     * TikTok return, following the simulator's SmartOmni requirements (phase 07b):
     * <ul>
     *   <li>the order status is never changed by a return (07b.2: the platform has no RETURNED order status);</li>
     *   <li>the request keeps the marketplace {@code return_status} and {@code update_time}; an event older than the
     *       stored {@code update_time} is ignored (07b.2);</li>
     *   <li>when the request reaches {@code RETURN_OR_REFUND_REQUEST_SUCCESS} or {@code _COMPLETE}, every returned
     *       line is restocked through an idempotent receipt keyed by (order, order line item), so a webhook replay or
     *       a polling overlap cannot restock twice (07b.4). The simulator never adds stock back itself.</li>
     * </ul>
     */
    private Order handleTiktokReturn(Long tenantId, Order order, ProcessReturnRequest request) {
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

        Instant incoming = platformInstant(request.getPlatformUpdateTime());
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
        refund.setStatus(tiktokRefundStatus(marketplaceStatus));
        refund = returnRefundRepository.save(refund);

        List<ReturnRefundItem> items = upsertTiktokReturnItems(tenantId, refund, request);

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
    static ReturnRefund.RefundStatus tiktokRefundStatus(String marketplaceStatus) {
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
    private List<ReturnRefundItem> upsertTiktokReturnItems(Long tenantId, ReturnRefund refund, ProcessReturnRequest request) {
        List<ReturnRefundItem> existing = returnRefundItemRepository == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(returnRefundItemRepository.findByTenantIdAndReturnRefundId(tenantId, refund.getId()));
        if (request.getItems() == null || returnRefundItemRepository == null) {
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

    private static ReturnRefund.RefundStatus partialRefundStatus(ProcessReturnRequest request) {
        ProcessReturnRequest.ReturnAction action = request.getReturnAction();
        if (action == ProcessReturnRequest.ReturnAction.RECEIVE_APPROVE || action == ProcessReturnRequest.ReturnAction.REFUND_ONLY) {
            return ReturnRefund.RefundStatus.COMPLETED;
        }
        if (action == ProcessReturnRequest.ReturnAction.RECEIVE_REJECT || action == ProcessReturnRequest.ReturnAction.RECEIVE_REJECTED) {
            return ReturnRefund.RefundStatus.REJECTED;
        }
        return ReturnRefund.RefundStatus.REQUESTED;
    }

    private boolean isBeforeCourierHandover(Order.OrderStatus previousStatus, Order order) {
        if (order.getCourierScannedAt() != null) {
            return false;
        }
        return previousStatus == Order.OrderStatus.PENDING
                || previousStatus == Order.OrderStatus.TO_SHIP
                || previousStatus == Order.OrderStatus.ON_HOLD;
    }

    private void restockOrderItems(Order order) {
        restockOrderItems(order, null);
    }

    private void restockOrderItems(Order order, List<ProcessReturnRequest.ReturnItemDto> returnItems) {
        if (order.getPlatform() == Order.Platform.TIKTOK_SHOP) {
            return; // TikTok stock is applied from the absolute inventory webhook snapshot.
        }
        if (returnItems != null && !returnItems.isEmpty()) {
            log.info("Hoàn trả tồn kho theo danh mục mặt hàng trả lại cho đơn hàng id={}, itemsCount={}",
                    order.getId(), returnItems.size());
            for (ProcessReturnRequest.ReturnItemDto item : returnItems) {
                if (item.getSkuId() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                    inventoryServiceClient.restock(item.getSkuId(), item.getQuantity());
                    log.info("Hoàn trả tồn kho thành công cho skuId={}, quantity={}", item.getSkuId(), item.getQuantity());
                }
            }
            return;
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        log.info("Tự động hoàn trả tồn kho cho đơn hàng CANCELLED/RETURNED id={}, itemsCount={}", order.getId(), items.size());
        for (OrderItem item : items) {
            if (item.getSkuId() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                inventoryServiceClient.restock(item.getSkuId(), item.getQuantity());
                log.info("Hoàn trả tồn kho thành công cho skuId={}, quantity={}", item.getSkuId(), item.getQuantity());
            }
        }
    }

    private void upsertReturnRecord(Long tenantId, Order order, ProcessReturnRequest request,
                                    ReturnRefund.RefundStatus status, ReturnRefund.InspectionResultType inspectionResult,
                                    boolean restocked) {
        if (returnRefundRepository == null || request.getReturnId() == null) {
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

        refund.setReturnType(request.getReturnType());
        refund.setReason(request.getReturnReason());
        refund.setStatus(status);
        refund.setInspectionResult(inspectionResult);
        refund.setRestocked(restocked);
        if (request.getRefundAmount() != null) {
            refund.setRefundAmount(request.getRefundAmount());
        }
        if (request.getEvidenceUrls() != null && !request.getEvidenceUrls().isEmpty()) {
            refund.setVideoEvidenceUrl(String.join(",", request.getEvidenceUrls()));
        }
        ReturnRefund savedRefund = returnRefundRepository.save(refund);
        Long refundId = savedRefund != null && savedRefund.getId() != null ? savedRefund.getId() : refund.getId();

        if (refundId != null && returnRefundItemRepository != null && request.getItems() != null && !request.getItems().isEmpty()) {
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

    private Long requireRequestTenant(Long requestedTenantId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || (requestedTenantId != null && !tenantId.equals(requestedTenantId))) {
            throw new ResourceNotFoundException("Đơn hàng không tồn tại trong tenant hiện tại");
        }
        return tenantId;
    }

    private void recordWalletLedger(Order order) {
        if (sellerWalletLedgerRepository.existsByOrderId(order.getId())) {
            log.info("Ledger doanh thu đã tồn tại cho orderId={}", order.getId());
            return;
        }

        BigDecimal grossAmount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal platformFee = grossAmount.multiply(new BigDecimal("0.05")); // 5% phí sàn tạm tính
        BigDecimal freeshipFee = BigDecimal.ZERO;
        BigDecimal netPayout = grossAmount.subtract(platformFee).subtract(freeshipFee);

        SellerWalletLedger ledger = new SellerWalletLedger();
        ledger.setTenantId(order.getTenantId());
        ledger.setOrderId(order.getId());
        ledger.setGrossAmount(grossAmount);
        ledger.setPlatformFee(platformFee);
        ledger.setFreeshipFee(freeshipFee);
        ledger.setNetPayout(netPayout);
        ledger.setRecordedAt(Instant.now());

        sellerWalletLedgerRepository.save(ledger);
        log.info("Đã ghi nhận doanh thu vào seller_wallet_ledger: orderId={}, gross={}, netPayout={}",
                order.getId(), grossAmount, netPayout);
    }

    private void recordStatusHistory(Order order, Order.OrderStatus status, OrderStatusHistory.OrderChangeSource source, Long changedBy) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setTenantId(order.getTenantId());
        history.setOrderId(order.getId());
        history.setStatus(status);
        history.setSource(source);
        history.setChangedBy(changedBy);
        history.setChangedAt(Instant.now());
        orderStatusHistoryRepository.save(history);
    }

    public Page<Order> getOrdersByTenant(Long tenantId, Pageable pageable) {
        return orderRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public OrderDetailDto getOrderDetail(Long orderId) {
        Long tenantId = TenantContext.getTenantId();
        Order order = orderRepository.findByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: " + orderId));
        List<OrderItem> items = orderItemRepository.findByOrderIdAndTenantId(orderId, tenantId);
        List<OrderItemDto> itemDtos = items.stream()
                .map(item -> OrderItemDto.builder()
                        .id(item.getId())
                        .skuId(item.getSkuId())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .subtotal(item.getUnitPrice() != null && item.getQuantity() != null
                                ? item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                                : null)
                        .imageUrl(item.getImageUrl())
                        .build())
                .toList();

        return OrderDetailDto.builder()
                .id(order.getId())
                .tenantId(order.getTenantId())
                .platform(order.getPlatform() != null ? order.getPlatform().name() : null)
                .connectionId(order.getConnectionId())
                .platformOrderId(order.getPlatformOrderId())
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .slaDeadline(order.getSlaDeadline())
                .shippingMethod(order.getShippingMethod())
                .trackingNumber(order.getTrackingNumber())
                .courierScannedAt(order.getCourierScannedAt())
                .customerName(order.getCustomerName())
                .customerPhone(order.getCustomerPhone())
                .shippingAddress(order.getShippingAddress())
                .totalAmount(order.getTotalAmount())
                .source(order.getSource() != null ? order.getSource().name() : null)
                .returnReason(order.getReturnReason())
                .rejectReason(order.getRejectReason())
                .restockStatus(order.getRestockStatus())
                .createdAt(order.getCreatedAt())
                .items(itemDtos)
                .build();
    }

    @Transactional
    public OrderDetailDto createManualOrder(CreateManualOrderRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("TenantId is required", HttpStatus.BAD_REQUEST, "TENANT_REQUIRED");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Đơn hàng phải chứa ít nhất 1 sản phẩm", HttpStatus.BAD_REQUEST, "ITEMS_REQUIRED");
        }

        Order.Platform platform = Order.Platform.SHOPEE;
        if (request.getPlatform() != null && !request.getPlatform().isBlank()) {
            try {
                platform = Order.Platform.valueOf(request.getPlatform().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        String platformOrderId = (request.getPlatformOrderId() != null && !request.getPlatformOrderId().isBlank())
                ? request.getPlatformOrderId()
                : "MANUAL-" + System.currentTimeMillis();

        BigDecimal computedTotal = BigDecimal.ZERO;
        for (CreateManualOrderRequest.ManualOrderItem item : request.getItems()) {
            if (item.getUnitPrice() != null && item.getQuantity() != null) {
                computedTotal = computedTotal.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
        }
        BigDecimal effectiveTotal = request.getTotalAmount() != null ? request.getTotalAmount() : computedTotal;

        Order order = new Order();
        order.setTenantId(tenantId);
        order.setPlatform(platform);
        order.setPlatformOrderId(platformOrderId);
        order.setCustomerName(request.getCustomerName() != null ? request.getCustomerName() : "Khách test");
        order.setCustomerPhone(request.getCustomerPhone());
        order.setShippingAddress(request.getShippingAddress());
        order.setShippingMethod(request.getShippingMethod() != null ? request.getShippingMethod() : "Giao hàng tiêu chuẩn");
        order.setTotalAmount(effectiveTotal);
        order.setStatus(Order.OrderStatus.PENDING);
        order.setSource(Order.OrderSource.MANUAL);

        order = orderRepository.save(order);

        for (CreateManualOrderRequest.ManualOrderItem reqItem : request.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setTenantId(tenantId);
            orderItem.setOrderId(order.getId());
            orderItem.setSkuId(reqItem.getSkuId() != null ? reqItem.getSkuId() : 0L);
            orderItem.setQuantity(reqItem.getQuantity() != null ? reqItem.getQuantity() : 1);
            orderItem.setUnitPrice(reqItem.getUnitPrice() != null ? reqItem.getUnitPrice() : BigDecimal.ZERO);
            orderItem.setImageUrl(reqItem.getImageUrl());
            orderItemRepository.save(orderItem);
        }

        recordStatusHistory(order, Order.OrderStatus.PENDING, OrderStatusHistory.OrderChangeSource.STAFF, TenantContext.getCurrentUserId());

        return getOrderDetail(order.getId());
    }

    /** UC-06: Khách hàng tra cứu đơn hàng bằng Order ID, không cần đăng nhập. */
    public Order trackOrder(String platform, String platformOrderId) {
        return orderRepository.findByPlatformAndPlatformOrderId(Order.Platform.valueOf(platform), platformOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng"));
    }
}
