package com.smartomni.order.service;

import com.smartomni.order.entity.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TiktokOrderStatusMapper Unit Tests")
class TiktokOrderStatusMapperTest {

    @ParameterizedTest
    @CsvSource({
            "UNPAID, PENDING",
            "100, PENDING",
            "unpaid, PENDING",
            "AWAITING_SHIPMENT, TO_SHIP",
            "111, TO_SHIP",
            "awaiting_shipment, TO_SHIP",
            "AWAITING_COLLECTION, SHIPPED",
            "112, SHIPPED",
            "IN_TRANSIT, SHIPPED",
            "121, SHIPPED",
            "PARTIALLY_SHIPPING, SHIPPED",
            "114, SHIPPED",
            "DELIVERED, COMPLETED",
            "122, COMPLETED",
            "COMPLETED, COMPLETED",
            "130, COMPLETED",
            "CANCELLED, CANCELLED",
            "CANCELED, CANCELLED",
            "140, CANCELLED",
            "IN_CANCEL, ON_HOLD",
            "ON_HOLD, ON_HOLD",
            "on_hold, ON_HOLD",
            "UNKNOWN_STATUS, PENDING",
            "'', PENDING"
    })
    @DisplayName("Ánh xạ chính xác trạng thái từ TikTok Shop sang OrderStatus")
    void testMapOrderStatus(String tiktokStatus, Order.OrderStatus expectedStatus) {
        Order.OrderStatus actualStatus = TiktokOrderStatusMapper.map(tiktokStatus);
        assertEquals(expectedStatus, actualStatus);
    }

    @Test
    @DisplayName("Null status trả về PENDING mặc định")
    void testMapNullStatus() {
        assertEquals(Order.OrderStatus.PENDING, TiktokOrderStatusMapper.map(null));
    }

    @Test
    @DisplayName("Kiểm tra tính hợp lệ của việc chuyển đổi trạng thái (State Machine)")
    void testValidTransitions() {
        // Valid forward transitions
        assertTrue(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.PENDING, Order.OrderStatus.TO_SHIP));
        assertTrue(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.PENDING, Order.OrderStatus.CANCELLED));
        assertTrue(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.PENDING, Order.OrderStatus.ON_HOLD));
        assertTrue(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.TO_SHIP, Order.OrderStatus.SHIPPED));
        assertTrue(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.TO_SHIP, Order.OrderStatus.CANCELLED));
        assertTrue(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.SHIPPED, Order.OrderStatus.COMPLETED));

        // Same status is always valid (no-op)
        assertTrue(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.TO_SHIP, Order.OrderStatus.TO_SHIP));

        // Invalid transitions from terminal states
        assertFalse(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.COMPLETED, Order.OrderStatus.TO_SHIP));
        assertFalse(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.CANCELLED, Order.OrderStatus.COMPLETED));
        assertFalse(TiktokOrderStatusMapper.isValidTransition(Order.OrderStatus.RETURNED, Order.OrderStatus.SHIPPED));
    }
}
