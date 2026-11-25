package com.smartomni.order.marketplace.shopee;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.Order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Shopee mapping and transition table, checked against shopee-simulator @ 4ce7c84 (plan 4.1). */
class ShopeeOrderPolicyTest {

    private final ShopeeOrderPolicy policy = new ShopeeOrderPolicy(null);

    @ParameterizedTest
    @CsvSource({
            "UNPAID, PENDING",
            "READY_TO_SHIP, TO_SHIP",
            "PROCESSED, TO_SHIP",
            "SHIPPED, SHIPPED",
            "TO_CONFIRM_RECEIVE, DELIVERING",
            "COMPLETED, COMPLETED",
            "IN_CANCEL, TO_SHIP",
            "CANCELLED, CANCELLED",
            "TO_RETURN, COMPLETED",
            "ready_to_ship, TO_SHIP"
    })
    void mapsAllNineSimulatorStatuses(String raw, OrderStatus expected) {
        assertEquals(expected, ShopeeOrderStatusMapper.map(raw));
        assertEquals(expected, policy.mapStatus(raw));
    }

    @ParameterizedTest
    @CsvSource({"IN_TRANSIT", "DELIVERED", "PENDING", "TO_SHIP", "RETURNED", "AWAITING_SHIPMENT", "''"})
    void canonicalOrTiktokValuesAreUnknown(String raw) {
        var error = assertThrows(BusinessException.class, () -> ShopeeOrderStatusMapper.map(raw));
        assertEquals("UNKNOWN_MARKETPLACE_ORDER_STATUS", error.getErrorCode());
    }

    @Test
    void nullIsUnknown() {
        assertThrows(BusinessException.class, () -> ShopeeOrderStatusMapper.map(null));
    }

    @ParameterizedTest
    @CsvSource({
            // forward, with skips (plan 3.2)
            "PENDING, TO_SHIP, true", "PENDING, COMPLETED, true", "TO_SHIP, COMPLETED, true", "SHIPPED, DELIVERING, true",
            // cancel only before handover
            "PENDING, CANCELLED, true", "TO_SHIP, CANCELLED, true", "SHIPPED, CANCELLED, false", "DELIVERING, CANCELLED, false",
            // no marketplace status leads to or out of ON_HOLD (only WF-05 sets it)
            "TO_SHIP, ON_HOLD, false", "PENDING, ON_HOLD, false",
            "ON_HOLD, TO_SHIP, false", "ON_HOLD, COMPLETED, false", "ON_HOLD, CANCELLED, false",
            // never backwards, terminals stay
            "SHIPPED, TO_SHIP, false", "COMPLETED, SHIPPED, false", "CANCELLED, TO_SHIP, false",
            // RETURNED only via WF-05
            "COMPLETED, RETURNED, false", "SHIPPED, RETURNED, false", "RETURNED, COMPLETED, false",
            "COMPLETED, COMPLETED, true"
    })
    void transitionTable(OrderStatus from, OrderStatus to, boolean allowed) {
        assertEquals(allowed, policy.canTransition(from, to), from + " -> " + to);
    }

    @Test
    void stockRules() {
        assertTrue(policy.deductStockOnCreate(OrderStatus.TO_SHIP));
        assertFalse(policy.deductStockOnCreate(OrderStatus.CANCELLED), "M9");

        Order order = new Order();
        assertTrue(policy.restockOnCancel(OrderStatus.TO_SHIP, order));
        assertFalse(policy.restockOnCancel(OrderStatus.ON_HOLD, order));
        assertFalse(policy.restockOnCancel(OrderStatus.SHIPPED, order));
        order.setCourierScannedAt(Instant.now());
        assertFalse(policy.restockOnCancel(OrderStatus.TO_SHIP, order));
    }
}
