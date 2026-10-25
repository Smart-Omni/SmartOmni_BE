package com.smartomni.order.service;

import com.smartomni.order.entity.Order;
import lombok.extern.slf4j.Slf4j;

/**
 * Mapper chuyển đổi trạng thái đơn hàng từ TikTok Shop sang OrderStatus chuẩn của SmartOmni.
 *
 * Ánh xạ:
 * - UNPAID (100) -> PENDING
 * - AWAITING_SHIPMENT (111) -> TO_SHIP
 * - AWAITING_COLLECTION (112), IN_TRANSIT (121), PARTIALLY_SHIPPING (114) -> SHIPPED
 * - DELIVERED (122), COMPLETED (130) -> COMPLETED
 * - CANCELLED (140) -> CANCELLED
 * - IN_CANCEL, ON_HOLD -> ON_HOLD
 */
@Slf4j
public final class TiktokOrderStatusMapper {

    private TiktokOrderStatusMapper() {
    }

    public static Order.OrderStatus map(String tiktokStatus) {
        if (tiktokStatus == null || tiktokStatus.isBlank()) {
            return Order.OrderStatus.PENDING;
        }

        String normalized = tiktokStatus.trim().toUpperCase();

        return switch (normalized) {
            case "UNPAID", "100" -> Order.OrderStatus.PENDING;
            case "AWAITING_SHIPMENT", "111" -> Order.OrderStatus.TO_SHIP;
            case "AWAITING_COLLECTION", "112", "IN_TRANSIT", "121", "PARTIALLY_SHIPPING", "114" -> Order.OrderStatus.SHIPPED;
            case "DELIVERED", "122", "COMPLETED", "130" -> Order.OrderStatus.COMPLETED;
            case "CANCELLED", "CANCELED", "140" -> Order.OrderStatus.CANCELLED;
            case "IN_CANCEL", "ON_HOLD" -> Order.OrderStatus.ON_HOLD;
            default -> {
                log.warn("Trạng thái TikTok không xác định '{}', fallback về PENDING", tiktokStatus);
                yield Order.OrderStatus.PENDING;
            }
        };
    }

    /**
     * Kiểm tra tính hợp lệ của việc chuyển đổi trạng thái (State Machine check).
     * Terminal states: COMPLETED, CANCELLED, RETURNED (không thể đổi tiếp).
     */
    public static boolean isValidTransition(Order.OrderStatus current, Order.OrderStatus next) {
        if (current == null || next == null) {
            return false;
        }
        if (current == next) {
            return true;
        }
        // Terminal states
        if (current == Order.OrderStatus.CANCELLED || current == Order.OrderStatus.RETURNED) {
            return false;
        }

        return switch (current) {
            case PENDING -> next == Order.OrderStatus.TO_SHIP || next == Order.OrderStatus.CANCELLED || next == Order.OrderStatus.ON_HOLD;
            case TO_SHIP -> next == Order.OrderStatus.SHIPPED || next == Order.OrderStatus.CANCELLED || next == Order.OrderStatus.ON_HOLD;
            case ON_HOLD -> next == Order.OrderStatus.TO_SHIP || next == Order.OrderStatus.CANCELLED;
            case SHIPPED -> next == Order.OrderStatus.DELIVERING || next == Order.OrderStatus.COMPLETED || next == Order.OrderStatus.RETURNED;
            case DELIVERING -> next == Order.OrderStatus.COMPLETED || next == Order.OrderStatus.RETURNED;
            case COMPLETED -> next == Order.OrderStatus.RETURNED;
            default -> false;
        };
    }
}
