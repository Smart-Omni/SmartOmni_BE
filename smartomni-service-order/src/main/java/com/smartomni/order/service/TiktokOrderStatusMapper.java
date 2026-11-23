package com.smartomni.order.service;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.entity.Order;
import org.springframework.http.HttpStatus;
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
            // The simulator stopped sending ON_HOLD (7f721af); production TikTok still can, so the mapping stays.
            // Nothing here depends on release_date.
            case "IN_CANCEL", "ON_HOLD" -> Order.OrderStatus.ON_HOLD;
            default -> {
                throw new BusinessException("Trạng thái sàn không xác định: " + tiktokStatus,
                        HttpStatus.BAD_REQUEST, "UNKNOWN_MARKETPLACE_ORDER_STATUS");
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
            case ON_HOLD -> next == Order.OrderStatus.TO_SHIP || next == Order.OrderStatus.CANCELLED || next == Order.OrderStatus.RETURNED;
            case SHIPPED -> next == Order.OrderStatus.DELIVERING || next == Order.OrderStatus.COMPLETED || next == Order.OrderStatus.RETURNED || next == Order.OrderStatus.ON_HOLD;
            case DELIVERING -> next == Order.OrderStatus.COMPLETED || next == Order.OrderStatus.RETURNED;
            case COMPLETED -> next == Order.OrderStatus.RETURNED;
            default -> false;
        };
    }
}
