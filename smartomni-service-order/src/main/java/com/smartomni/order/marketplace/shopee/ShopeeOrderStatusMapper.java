package com.smartomni.order.marketplace.shopee;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.entity.Order;
import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Raw Shopee order status to SmartOmni status (plan section 4.1).
 * <p>
 * Source: {@code shopee-simulator/prisma/schema.prisma} enum {@code OrderStatus} @ 4ce7c84:
 * UNPAID, READY_TO_SHIP, PROCESSED, SHIPPED, TO_CONFIRM_RECEIVE, COMPLETED, IN_CANCEL, CANCELLED, TO_RETURN.
 * <ul>
 *   <li>IN_CANCEL is a buyer cancel request the seller may still REJECT (back to READY_TO_SHIP/PROCESSED), so it is
 *       ON_HOLD, not CANCELLED.</li>
 *   <li>TO_RETURN is only entered from COMPLETED and goes back to COMPLETED when the return closes; the return itself
 *       is tracked in {@code return_refund}. RETURNED is set only by WF-05 inspection (decision D1).</li>
 *   <li>TO_CONFIRM_RECEIVE is never emitted by the simulator; kept for real Shopee.</li>
 * </ul>
 */
public final class ShopeeOrderStatusMapper {

    private ShopeeOrderStatusMapper() {
    }

    public static Order.OrderStatus map(String shopeeStatus) {
        String normalized = shopeeStatus == null ? "" : shopeeStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "UNPAID" -> Order.OrderStatus.PENDING;
            case "READY_TO_SHIP", "PROCESSED" -> Order.OrderStatus.TO_SHIP;
            case "SHIPPED" -> Order.OrderStatus.SHIPPED;
            case "TO_CONFIRM_RECEIVE" -> Order.OrderStatus.DELIVERING;
            case "COMPLETED", "TO_RETURN" -> Order.OrderStatus.COMPLETED;
            case "IN_CANCEL" -> Order.OrderStatus.ON_HOLD;
            case "CANCELLED" -> Order.OrderStatus.CANCELLED;
            default -> throw new BusinessException("Trạng thái Shopee không xác định: " + shopeeStatus,
                    HttpStatus.BAD_REQUEST, "UNKNOWN_MARKETPLACE_ORDER_STATUS");
        };
    }
}
