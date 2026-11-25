package com.smartomni.order.marketplace;

import com.smartomni.order.entity.Order.OrderStatus;

import java.time.Instant;
import java.util.List;

/**
 * Rules shared by every marketplace policy (plan section 3.2). The cancel side branch stays in each platform's own
 * table. No marketplace status maps to ON_HOLD: neither simulator has it on its main flow (TikTok DECISIONS H1, and
 * Shopee IN_CANCEL keeps the order at TO_SHIP).
 */
public final class OrderStatusFlow {

    /** Main line of an order. Polling (every 3 min) and lost webhooks only see the latest state, so skips are allowed. */
    private static final List<OrderStatus> MAIN_LINE = List.of(
            OrderStatus.PENDING, OrderStatus.TO_SHIP, OrderStatus.SHIPPED, OrderStatus.DELIVERING, OrderStatus.COMPLETED);

    private OrderStatusFlow() {
    }

    /** True when both are on the main line and {@code to} is strictly later (skips allowed, never backwards). */
    public static boolean isForward(OrderStatus from, OrderStatus to) {
        int f = MAIN_LINE.indexOf(from);
        int t = MAIN_LINE.indexOf(to);
        return f >= 0 && t > f;
    }

    /** Platform epoch seconds (or millis, as some create_time values are) to an Instant; null when absent. */
    public static Instant platformInstant(Long epoch) {
        if (epoch == null || epoch <= 0) {
            return null;
        }
        return epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
    }
}
