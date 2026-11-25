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
import com.smartomni.order.entity.SellerWalletLedger;
import com.smartomni.order.marketplace.MarketplaceOrderPolicy;
import com.smartomni.order.marketplace.MarketplaceOrderPolicyRegistry;
import com.smartomni.order.marketplace.OrderStatusFlow;
import com.smartomni.order.repository.OrderItemRepository;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.OrderStatusHistoryRepository;
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
 * <p>
 * Mapping, transition table, stock and return rules are per marketplace ({@link MarketplaceOrderPolicy}); this class
 * keeps only what every marketplace shares: idempotency, staleness by platform update time, the staff rule, history,
 * wallet ledger and tenant scope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    /** Reconciliation reason for a marketplace status change that the platform's table does not allow. */
    public static final String RECONCILIATION_STATUS_CONFLICT = "STATUS_CONFLICT";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final SellerWalletLedgerRepository sellerWalletLedgerRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final MarketplaceOrderPolicyRegistry policies;

    /**
     * Được gọi bởi RabbitMQ Consumer (OrderMessageConsumer) - nội dung chính của UC-33.
     * FR-025: kiểm tra idempotency trước khi tạo mới hoặc cập nhật trạng thái nếu đơn đã tồn tại.
     */
    @Transactional
    public void processIncomingOrder(WebhookOrderPayload payload, Order.OrderSource source) {
        Order.Platform platform = Order.Platform.valueOf(payload.getPlatform());
        MarketplaceOrderPolicy policy = policies.forPlatform(platform);

        Optional<Order> existing = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(
                payload.getTenantId(), platform, payload.getPlatformOrderId());

        Instant incomingUpdate = OrderStatusFlow.platformInstant(payload.getPlatformUpdateTime());

        if (existing.isPresent()) {
            Order order = existing.get();
            log.info("Đơn hàng {} đã tồn tại (tenant={}, source={})",
                    payload.getPlatformOrderId(), payload.getTenantId(), source);

            // Webhooks can arrive out of order and polling can replay old state: never move an order back.
            // Without a platform time on either side the comparison is skipped.
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
            applyMilestones(order, payload);

            if (payload.getOrderStatus() != null && !payload.getOrderStatus().isBlank()) {
                Order.OrderStatus incomingStatus = policy.mapStatus(payload.getOrderStatus());
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
        applyMilestones(order, payload);

        Order.OrderStatus initialStatus = payload.getOrderStatus() != null && !payload.getOrderStatus().isBlank()
                ? policy.mapStatus(payload.getOrderStatus())
                : Order.OrderStatus.TO_SHIP;
        order.setStatus(initialStatus);
        if (initialStatus == Order.OrderStatus.COMPLETED) {
            markCompletedIfNeeded(order, policy);
        }

        order = orderRepository.save(order);
        recordStatusHistory(order, initialStatus, OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK, null);
        if (initialStatus == Order.OrderStatus.COMPLETED) {
            recordWalletLedger(order);
        }

        boolean deductStock = policy.deductStockOnCreate(initialStatus);
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

                // FR-027, FR-028: trừ kho nếu sàn yêu cầu và đã map được internal skuId
                if (deductStock && itemPayload.getSkuId() != null
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
     * 3. Hoàn trả tồn kho nếu CANCELLED và sàn yêu cầu (policy).
     * 4. Ghi nhận doanh thu vào seller_wallet_ledger nếu COMPLETED.
     * <p>
     * A staff change that is not allowed throws. A marketplace change that the platform's table does not allow is
     * logged and skipped (no exception, so the message is not retried into the DLQ) and the order is flagged
     * {@link #RECONCILIATION_STATUS_CONFLICT}.
     */
    @Transactional
    public Order updateOrderStatus(Order order, Order.OrderStatus newStatus, OrderStatusHistory.OrderChangeSource source) {
        Order.OrderStatus previousStatus = order.getStatus();
        if (previousStatus == newStatus) {
            return order;
        }
        MarketplaceOrderPolicy policy = policies.forPlatform(order.getPlatform());

        if (source == OrderStatusHistory.OrderChangeSource.STAFF) {
            if (previousStatus != Order.OrderStatus.PENDING || newStatus != Order.OrderStatus.TO_SHIP) {
                throw new BusinessException("Nhân viên chỉ được chuyển PENDING sang TO_SHIP",
                        HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION");
            }
        } else if (!policy.canTransition(previousStatus, newStatus)) {
            if (previousStatus == Order.OrderStatus.RETURNED && newStatus == Order.OrderStatus.COMPLETED) {
                // RETURNED is set by WF-05 after COMPLETED; the marketplace keeps reporting the order as completed.
                log.info("Đơn {} đã RETURNED (WF-05), bỏ qua trạng thái sàn {}", order.getPlatformOrderId(), newStatus);
                return order;
            }
            log.warn("Bỏ qua chuyển trạng thái không hợp lệ của {} cho đơn {}: {} -> {} (source={})",
                    order.getPlatform(), order.getPlatformOrderId(), previousStatus, newStatus, source);
            if (order.getReconciliationReason() == null) {
                order.setReconciliationReason(RECONCILIATION_STATUS_CONFLICT);
            }
            return orderRepository.save(order);
        }

        order.setStatus(newStatus);
        if (newStatus == Order.OrderStatus.COMPLETED) {
            markCompletedIfNeeded(order, policy);
        }
        order = orderRepository.save(order);

        // 1. Ghi nhận dòng lịch sử thay đổi trạng thái
        recordStatusHistory(order, newStatus, source, null);

        // 2. Nếu đơn chuyển sang CANCELLED và sàn yêu cầu SmartOmni tự hoàn kho
        if (newStatus == Order.OrderStatus.CANCELLED && policy.restockOnCancel(previousStatus, order)) {
            restockOrderItems(order);
        }

        // 3. Nếu đơn chuyển sang COMPLETED: ghi nhận doanh thu vào seller_wallet_ledger
        if (newStatus == Order.OrderStatus.COMPLETED) {
            recordWalletLedger(order);
        }

        return order;
    }

    /**
     * Stores the marketplace milestones of a non-stale message (plan 4.3). A missing field never clears a stored value;
     * the milestones are display data only and never touch the status, completed_at, the wallet or loyalty.
     */
    private static void applyMilestones(Order order, WebhookOrderPayload payload) {
        Instant rts = OrderStatusFlow.platformInstant(payload.getPlatformRtsTime());
        Instant collected = OrderStatusFlow.platformInstant(payload.getPlatformCollectionTime());
        Instant delivered = OrderStatusFlow.platformInstant(payload.getPlatformDeliveryTime());
        Instant cancelled = OrderStatusFlow.platformInstant(payload.getPlatformCancelTime());
        if (rts != null) {
            order.setPlatformRtsAt(rts);
        }
        if (collected != null) {
            order.setPlatformCollectedAt(collected);
        }
        if (delivered != null) {
            order.setPlatformDeliveredAt(delivered);
        }
        if (cancelled != null) {
            order.setPlatformCancelledAt(cancelled);
        }
    }

    private void markCompletedIfNeeded(Order order, MarketplaceOrderPolicy policy) {
        if (order.getCompletedAt() == null) {
            Instant completedAt = policy.completedAt(order);
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
        Order.Platform p = requirePlatform(platform);
        Order order = orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(tenantId, p, platformOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: platform=" + platform + ", id=" + platformOrderId));
        Order.OrderStatus newStatus = policies.forPlatform(p).mapStatus(rawPlatformStatus);
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
        Order.Platform platform = requirePlatform(request.getPlatform());

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
                // Nếu trạng thái hiện tại là TO_SHIP hoặc PENDING -> chuyển sang SHIPPED
                if (order.getStatus() == Order.OrderStatus.TO_SHIP
                        || order.getStatus() == Order.OrderStatus.PENDING) {
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
     * Xử lý yêu cầu Trả hàng / Hoàn tiền từ sàn (Returns & Refund Management - Bước 4.2).
     * The rules belong to the order's marketplace policy; no marketplace return ever sets RETURNED (only WF-05 does).
     */
    @Transactional
    public Order handleOrderReturn(ProcessReturnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ProcessReturnRequest must not be null");
        }
        Long tenantId = requireRequestTenant(request.getTenantId());
        Order.Platform platform = requirePlatform(request.getPlatform());

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
        return policies.forPlatform(order.getPlatform()).handleReturn(tenantId, order, request);
    }

    /** Restocks every order item once (cancel before handover, for platforms whose policy asks for it). */
    private void restockOrderItems(Order order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        log.info("Tự động hoàn trả tồn kho cho đơn hàng CANCELLED id={}, itemsCount={}", order.getId(), items.size());
        for (OrderItem item : items) {
            if (item.getSkuId() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                inventoryServiceClient.restock(item.getSkuId(), item.getQuantity());
                log.info("Hoàn trả tồn kho thành công cho skuId={}, quantity={}", item.getSkuId(), item.getQuantity());
            }
        }
    }

    /** Marketplace messages must name their platform; a missing one is a 400, never a guess (M10). */
    private static Order.Platform requirePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new BusinessException("platform is required", HttpStatus.BAD_REQUEST, "PLATFORM_REQUIRED");
        }
        try {
            return Order.Platform.valueOf(platform.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unknown platform: " + platform, HttpStatus.BAD_REQUEST, "UNKNOWN_PLATFORM");
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
                .platformRtsAt(order.getPlatformRtsAt())
                .platformCollectedAt(order.getPlatformCollectedAt())
                .platformDeliveredAt(order.getPlatformDeliveredAt())
                .platformCancelledAt(order.getPlatformCancelledAt())
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
