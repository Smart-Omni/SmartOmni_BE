package com.smartomni.order.marketplace.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.order.entity.Order;
import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Raw TikTok Shop order status to SmartOmni status (plan section 4.2).
 * <p>
 * Source: {@code Smartomni-Tiktok-Simulator/backend/src/orders/order-state.ts} {@code ORDER_STATUSES} @ a830fce, plus
 * ON_HOLD from the OAS status list (the simulator never emits it, DECISIONS H1; real TikTok can).
 * <ul>
 *   <li>DELIVERED is DELIVERING, not COMPLETED: phase-09 requires COMPLETED for loyalty ("DELIVERED is not enough")
 *       and DELIVERED → COMPLETED is a separate status change in the simulator (decision D3).</li>
 *   <li>PARTIALLY_SHIPPING / AWAITING_COLLECTION are SHIPPED: from a830fce they can no longer be cancelled (D2).</li>
 *   <li>IN_CANCEL is in neither the simulator nor the OAS, so it is an unknown status.</li>
 * </ul>
 */
public final class TiktokOrderStatusMapper {

    private TiktokOrderStatusMapper() {
    }

    public static Order.OrderStatus map(String tiktokStatus) {
        if (tiktokStatus == null || tiktokStatus.isBlank()) {
            return Order.OrderStatus.PENDING;
        }
        return switch (tiktokStatus.trim().toUpperCase(Locale.ROOT)) {
            case "UNPAID", "100" -> Order.OrderStatus.PENDING;
            case "AWAITING_SHIPMENT", "111" -> Order.OrderStatus.TO_SHIP;
            case "AWAITING_COLLECTION", "112", "IN_TRANSIT", "121", "PARTIALLY_SHIPPING", "114" -> Order.OrderStatus.SHIPPED;
            case "DELIVERED", "122" -> Order.OrderStatus.DELIVERING;
            case "COMPLETED", "130" -> Order.OrderStatus.COMPLETED;
            case "CANCELLED", "CANCELED", "140" -> Order.OrderStatus.CANCELLED;
            case "ON_HOLD" -> Order.OrderStatus.ON_HOLD;
            default -> throw new BusinessException("Trạng thái TikTok không xác định: " + tiktokStatus,
                    HttpStatus.BAD_REQUEST, "UNKNOWN_MARKETPLACE_ORDER_STATUS");
        };
    }
}
