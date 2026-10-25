package com.smartomni.order.service;

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
