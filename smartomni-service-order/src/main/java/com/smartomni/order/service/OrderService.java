package com.smartomni.order.service;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.common.dto.UpdateOrderTrackingRequest;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.order.client.InventoryServiceClient;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderItem;
import com.smartomni.order.entity.OrderStatusHistory;
import com.smartomni.order.entity.SellerWalletLedger;
import com.smartomni.order.repository.OrderItemRepository;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.OrderStatusHistoryRepository;
import com.smartomni.order.repository.SellerWalletLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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

    /**
     * Được gọi bởi RabbitMQ Consumer (OrderMessageConsumer) - nội dung chính của UC-33.
     * FR-025: kiểm tra idempotency trước khi tạo mới hoặc cập nhật trạng thái nếu đơn đã tồn tại.
     */
    @Transactional
    public void processIncomingOrder(WebhookOrderPayload payload, Order.OrderSource source) {
        Order.Platform platform = Order.Platform.valueOf(payload.getPlatform());

        Optional<Order> existing = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(
                payload.getTenantId(), platform, payload.getPlatformOrderId());

        if (existing.isPresent()) {
            Order order = existing.get();
            log.info("Đơn hàng {} đã tồn tại (tenant={}, source={})",
                    payload.getPlatformOrderId(), payload.getTenantId(), source);

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

        Order.OrderStatus initialStatus = payload.getOrderStatus() != null && !payload.getOrderStatus().isBlank()
                ? TiktokOrderStatusMapper.map(payload.getOrderStatus())
                : Order.OrderStatus.TO_SHIP;
        order.setStatus(initialStatus);

        order = orderRepository.save(order);
        recordStatusHistory(order, initialStatus, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK, null);

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
                if (itemPayload.getSkuId() != null && itemPayload.getQuantity() != null && itemPayload.getQuantity() > 0) {
                    try {
                        inventoryServiceClient.deductStock(itemPayload.getSkuId(), itemPayload.getQuantity());
                    } catch (Exception ex) {
                        log.error("Trừ kho thất bại cho SKU {}: {}", itemPayload.getSkuId(), ex.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Cập nhật trạng thái đơn hàng (cho Manager hoặc Webhook).
     */
    @Transactional
    public Order updateStatus(Long orderId, Order.OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: " + orderId));
        return updateOrderStatus(order, newStatus, OrderStatusHistory.OrderChangeSource.MANAGER);
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

        if (!TiktokOrderStatusMapper.isValidTransition(previousStatus, newStatus)) {
            log.warn("Chuyển trạng thái bất thường từ {} sang {} cho orderId={}", previousStatus, newStatus, order.getId());
        }

        order.setStatus(newStatus);
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
        Long tenantId = request.getTenantId();
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
            if (tenantId != null && tenantId > 0) {
                orderOpt = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(tenantId, platform, platformOrderId);
            } else {
                orderOpt = orderRepository.findByPlatformAndPlatformOrderId(platform, platformOrderId);
            }

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
        Long tenantId = request.getTenantId();
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
        if (tenantId != null && tenantId > 0) {
            orderOpt = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(tenantId, platform, platformOrderId);
        } else {
            orderOpt = orderRepository.findByPlatformAndPlatformOrderId(platform, platformOrderId);
        }

        if (orderOpt.isEmpty()) {
            log.warn("Không tìm thấy đơn hàng cho yêu cầu trả hàng: tenantId={}, platform={}, orderId={}",
                    tenantId, platform, platformOrderId);
            return null;
        }

        Order order = orderOpt.get();
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
                case RECEIVE_APPROVE -> {
                    log.info("Xác nhận trả hàng thành công (RECEIVE_APPROVE) cho orderId={}, returnId={}",
                            platformOrderId, request.getReturnId());
                    updateOrderStatus(order, Order.OrderStatus.RETURNED, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    if (isRestockRequired) {
                        log.info("Yêu cầu nhập lại tồn kho cho đơn hàng hoàn trả id={}", order.getId());
                        restockOrderItems(order);
                        order.setRestockStatus("RESTOCKED");
                    } else {
                        order.setRestockStatus("SKIPPED");
                    }
                }
                case REFUND_ONLY -> {
                    log.info("Hoàn tiền không hoàn hàng vật lý (REFUND_ONLY) cho orderId={}, returnId={}. Nghiêm cấm hoàn kho.",
                            platformOrderId, request.getReturnId());
                    updateOrderStatus(order, Order.OrderStatus.RETURNED, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    order.setRestockStatus("NO_RESTOCK_REFUND_ONLY");
                    // Nghiêm cấm gọi restock
                }
                case RECEIVE_REJECT, RECEIVE_REJECTED -> {
                    log.warn("Từ chối nhận hàng hoàn / trả hàng (RECEIVE_REJECT) cho orderId={}, returnId={}, rejectReason={}. Nghiêm cấm hoàn kho.",
                            platformOrderId, request.getReturnId(), request.getRejectReason());
                    updateOrderStatus(order, Order.OrderStatus.ON_HOLD, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    order.setRestockStatus("REJECTED");
                    // Nghiêm cấm gọi restock
                }
                case DISPUTE_CLAIM, DISPUTE_APPROVED -> {
                    log.info("Xử lý khiếu nại sàn ({}) cho orderId={}, returnId={}. Nghiêm cấm hoàn kho.",
                            returnAction, platformOrderId, request.getReturnId());
                    Order.OrderStatus targetStatus = (returnAction == ProcessReturnRequest.ReturnAction.DISPUTE_APPROVED)
                            ? Order.OrderStatus.RETURNED
                            : Order.OrderStatus.ON_HOLD;
                    updateOrderStatus(order, targetStatus, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK);
                    order.setRestockStatus("DISPUTED");
                    // Nghiêm cấm gọi restock
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

                if (isRestockRequired && !"REFUND_ONLY".equalsIgnoreCase(request.getReturnType())) {
                    log.info("Yêu cầu nhập lại tồn kho cho đơn hàng hoàn trả id={}", order.getId());
                    restockOrderItems(order);
                    order.setRestockStatus("RESTOCKED");
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


    private boolean isBeforeCourierHandover(Order.OrderStatus previousStatus, Order order) {
        if (order.getCourierScannedAt() != null) {
            return false;
        }
        return previousStatus == Order.OrderStatus.PENDING
                || previousStatus == Order.OrderStatus.TO_SHIP
                || previousStatus == Order.OrderStatus.ON_HOLD;
    }

    private void restockOrderItems(Order order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        log.info("Tự động hoàn trả tồn kho cho đơn hàng CANCELLED id={}, itemsCount={}", order.getId(), items.size());
        for (OrderItem item : items) {
            if (item.getSkuId() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                try {
                    inventoryServiceClient.restock(item.getSkuId(), item.getQuantity());
                    log.info("Hoàn trả tồn kho thành công cho skuId={}, quantity={}", item.getSkuId(), item.getQuantity());
                } catch (Exception ex) {
                    log.error("Hoàn trả tồn kho thất bại cho SKU {}: {}", item.getSkuId(), ex.getMessage());
                }
            }
        }
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

    /** UC-06: Khách hàng tra cứu đơn hàng bằng Order ID, không cần đăng nhập. */
    public Order trackOrder(String platform, String platformOrderId) {
        return orderRepository.findByPlatformAndPlatformOrderId(Order.Platform.valueOf(platform), platformOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng"));
    }
}
