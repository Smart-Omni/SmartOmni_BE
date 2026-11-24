package com.smartomni.order.marketplace.tiktok;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.Order.OrderStatus;
import com.smartomni.order.marketplace.MarketplaceOrderPolicy;
import com.smartomni.order.marketplace.OrderStatusFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * TikTok Shop order rules (plan section 4.2), derived from {@code TRANSITIONS} in
 * {@code Smartomni-Tiktok-Simulator/backend/src/orders/order-state.ts} @ a830fce:
 * <pre>
 * UNPAID              → AWAITING_SHIPMENT | CANCELLED
 * AWAITING_SHIPMENT   → PARTIALLY_SHIPPING | AWAITING_COLLECTION | CANCELLED
 * PARTIALLY_SHIPPING  → AWAITING_COLLECTION
 * AWAITING_COLLECTION → IN_TRANSIT
 * IN_TRANSIT          → DELIVERED
 * DELIVERED           → COMPLETED
 * COMPLETED, CANCELLED: terminal
 * </pre>
 * Besides the forward main line: PENDING/TO_SHIP → CANCELLED; PENDING/TO_SHIP → ON_HOLD (real TikTok only);
 * ON_HOLD → TO_SHIP (or later) / CANCELLED; no cancel from SHIPPED/DELIVERING. A TikTok order never becomes RETURNED:
 * returns do not change the order status (phase-07, 07b.2).
 * <p>
 * Stock: the simulator reserves and releases stock itself and SmartOmni applies the absolute quantity from
 * {@code INVENTORY_CHANGED_BY_SHOP} (type 71), so no deduct on create and no restock on cancel.
 */
@Component
@RequiredArgsConstructor
public class TiktokOrderPolicy implements MarketplaceOrderPolicy {

    private final TiktokReturnHandler returnHandler;

    @Override
    public Order.Platform platform() {
        return Order.Platform.TIKTOK_SHOP;
    }

    @Override
    public OrderStatus mapStatus(String rawPlatformStatus) {
        return TiktokOrderStatusMapper.map(rawPlatformStatus);
    }

    @Override
    public boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return switch (from) {
            case PENDING, TO_SHIP -> OrderStatusFlow.isForward(from, to)
                    || to == OrderStatus.CANCELLED || to == OrderStatus.ON_HOLD;
            case ON_HOLD -> OrderStatusFlow.isResumeFromHold(to) || to == OrderStatus.CANCELLED;
            case SHIPPED, DELIVERING -> OrderStatusFlow.isForward(from, to);
            case COMPLETED, CANCELLED, RETURNED -> false;
        };
    }

    @Override
    public boolean deductStockOnCreate(OrderStatus initialStatus) {
        return false;
    }

    @Override
    public boolean restockOnCancel(OrderStatus previousStatus, Order order) {
        return false;
    }

    @Override
    public Order handleReturn(Long tenantId, Order order, ProcessReturnRequest request) {
        return returnHandler.handle(tenantId, order, request);
    }

    /**
     * phase-09: the completion time is the {@code update_time} of the change to COMPLETED (the simulator has no
     * completion field, and its business clock may run in the past), so the platform time is used, not now().
     */
    @Override
    public Instant completedAt(Order order) {
        return order.getPlatformUpdatedAt() != null ? order.getPlatformUpdatedAt() : Instant.now();
    }
}
