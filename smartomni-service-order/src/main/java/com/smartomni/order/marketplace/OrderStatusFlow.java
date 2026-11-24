package com.smartomni.order.marketplace;

import com.smartomni.order.entity.Order.OrderStatus;

import java.time.Instant;
import java.util.List;

/**
 * Rules shared by every marketplace policy (plan section 3.2). Side branches such as cancel and ON_HOLD stay in each
 * platform's own table.
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

    /**
     * A held order resumes at TO_SHIP or later. Later steps are accepted too, because the release event may have been
     * missed and the next state seen is already SHIPPED or beyond.
     */
    public static boolean isResumeFromHold(OrderStatus to) {
        return MAIN_LINE.indexOf(to) >= MAIN_LINE.indexOf(OrderStatus.TO_SHIP);
    }

    /** Platform epoch seconds (or millis, as some create_time values are) to an Instant; null when absent. */
    public static Instant platformInstant(Long epoch) {
        if (epoch == null || epoch <= 0) {
            return null;
        }
        return epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
    }
}
