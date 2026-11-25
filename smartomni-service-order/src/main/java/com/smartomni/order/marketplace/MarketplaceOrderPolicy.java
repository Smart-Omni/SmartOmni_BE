package com.smartomni.order.marketplace;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.order.entity.Order;

import java.time.Instant;

/**
 * Marketplace-specific order rules. Each platform has exactly one implementation, written against that platform's
 * simulator (see the implementation's source comments). {@code OrderService} only talks to this interface and never
 * branches on the platform itself.
 */
public interface MarketplaceOrderPolicy {

    Order.Platform platform();

    /**
     * Maps a raw status of THIS platform. Unknown values throw {@code UNKNOWN_MARKETPLACE_ORDER_STATUS}; nothing is
     * guessed.
     */
    Order.OrderStatus mapStatus(String rawPlatformStatus);

    /** Transition table of this platform, applied to marketplace-originated changes. {@code from == to} is a no-op. */
    boolean canTransition(Order.OrderStatus from, Order.OrderStatus to);

    /** Whether SmartOmni deducts stock itself when a new order of this platform first arrives. */
    boolean deductStockOnCreate(Order.OrderStatus initialStatus);

    /** Whether SmartOmni restocks the order items itself when the order becomes CANCELLED. */
    boolean restockOnCancel(Order.OrderStatus previousStatus, Order order);

    /** Applies a marketplace return/refund event. Never sets {@code RETURNED}: only WF-05 inspection does. */
    Order handleReturn(Long tenantId, Order order, ProcessReturnRequest request);

    /** The completion time to store when the order becomes COMPLETED. */
    default Instant completedAt(Order order) {
        return Instant.now();
    }
}
