package com.smartomni.order.marketplace.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.Order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TikTok mapping and transition table, checked against
 * Smartomni-Tiktok-Simulator/backend/src/orders/order-state.ts @ a830fce (plan 4.2).
 */
class TiktokOrderPolicyTest {

    private final TiktokOrderPolicy policy = new TiktokOrderPolicy(null);

    @ParameterizedTest
    @CsvSource({
            "UNPAID, PENDING", "100, PENDING", "unpaid, PENDING",
            "AWAITING_SHIPMENT, TO_SHIP", "111, TO_SHIP",
            "PARTIALLY_SHIPPING, SHIPPED", "114, SHIPPED",
            "AWAITING_COLLECTION, SHIPPED", "112, SHIPPED",
            "IN_TRANSIT, SHIPPED", "121, SHIPPED",
            // D3: DELIVERED is not COMPLETED (phase-09 "DELIVERED is not enough"); was COMPLETED before
            "DELIVERED, DELIVERING", "122, DELIVERING",
            "COMPLETED, COMPLETED", "130, COMPLETED",
            "CANCELLED, CANCELLED", "CANCELED, CANCELLED", "140, CANCELLED",
            "ON_HOLD, ON_HOLD", "'', PENDING"
    })
    void mapsSimulatorStatusesAndOnHold(String raw, OrderStatus expected) {
        assertEquals(expected, TiktokOrderStatusMapper.map(raw));
    }

    @Test
    void nullIsPending() {
        assertEquals(OrderStatus.PENDING, TiktokOrderStatusMapper.map(null));
    }

    @ParameterizedTest
    @CsvSource({"IN_CANCEL", "UNKNOWN_STATUS", "READY_TO_SHIP", "TO_RETURN"})
    void inCancelAndShopeeValuesAreUnknown(String raw) {
        // M5: IN_CANCEL is in neither the simulator nor the OAS.
        var error = assertThrows(BusinessException.class, () -> TiktokOrderStatusMapper.map(raw));
        assertEquals("UNKNOWN_MARKETPLACE_ORDER_STATUS", error.getErrorCode());
    }

    /** TRANSITIONS @ a830fce, copied verbatim. */
    private static final Map<String, List<String>> SIMULATOR_TRANSITIONS = Map.of(
            "UNPAID", List.of("AWAITING_SHIPMENT", "CANCELLED"),
            "AWAITING_SHIPMENT", List.of("PARTIALLY_SHIPPING", "AWAITING_COLLECTION", "CANCELLED"),
            "PARTIALLY_SHIPPING", List.of("AWAITING_COLLECTION"),
            "AWAITING_COLLECTION", List.of("IN_TRANSIT"),
            "IN_TRANSIT", List.of("DELIVERED"),
            "DELIVERED", List.of("COMPLETED"),
            "COMPLETED", List.of(),
            "CANCELLED", List.of());

    @Test
    void everySimulatorTransitionIsAccepted() {
        SIMULATOR_TRANSITIONS.forEach((from, targets) -> targets.forEach(to -> assertTrue(
                policy.canTransition(TiktokOrderStatusMapper.map(from), TiktokOrderStatusMapper.map(to)),
                from + " -> " + to)));
    }

    @Test
    void cancelAfterShipmentArrangedIsRejected() {
        for (String from : List.of("PARTIALLY_SHIPPING", "AWAITING_COLLECTION", "IN_TRANSIT", "DELIVERED", "COMPLETED")) {
            assertFalse(policy.canTransition(TiktokOrderStatusMapper.map(from), OrderStatus.CANCELLED), from);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "TO_SHIP, COMPLETED, true", "PENDING, DELIVERING, true",
            "PENDING, ON_HOLD, true", "TO_SHIP, ON_HOLD, true", "SHIPPED, ON_HOLD, false",
            "ON_HOLD, TO_SHIP, true", "ON_HOLD, CANCELLED, true",
            "DELIVERING, SHIPPED, false", "COMPLETED, DELIVERING, false", "CANCELLED, TO_SHIP, false",
            "COMPLETED, RETURNED, false", "DELIVERING, RETURNED, false"
    })
    void sideBranchesAndNoBackwards(OrderStatus from, OrderStatus to, boolean allowed) {
        assertEquals(allowed, policy.canTransition(from, to), from + " -> " + to);
    }

    @Test
    void neverTouchesStockAndCompletesAtPlatformTime() {
        Order order = new Order();
        assertFalse(policy.deductStockOnCreate(OrderStatus.TO_SHIP));
        assertFalse(policy.restockOnCancel(OrderStatus.TO_SHIP, order));

        order.setPlatformUpdatedAt(Instant.ofEpochSecond(1_700_000_000L));
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), policy.completedAt(order));
    }
}
