package com.smartomni.order.marketplace.shopee;

import com.smartomni.common.dto.ProcessReturnRequest;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.Order.OrderStatus;
import com.smartomni.order.marketplace.MarketplaceOrderPolicy;
import com.smartomni.order.marketplace.OrderStatusFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Shopee order rules (plan section 4.1), checked against {@code shopee-simulator} @ 4ce7c84
 * ({@code order.router.ts}, {@code simulator.router.ts}, {@code returns.router.ts}).
 * <p>
 * Transitions besides the forward main line:
 * <ul>
 *   <li>PENDING, TO_SHIP → CANCELLED: seller cancel or SLA expiry (UNPAID/READY_TO_SHIP → CANCELLED), and seller
 *       ACCEPT of a buyer cancel request (IN_CANCEL stays TO_SHIP, see {@link ShopeeOrderStatusMapper});</li>
 *   <li>SHIPPED, DELIVERING: no cancel (the simulator has none after handover);</li>
 *   <li>COMPLETED → RETURNED only through WF-05, never through this table;</li>
 *   <li>ON_HOLD is never reached from Shopee; an order WF-05 put on hold accepts no marketplace status.</li>
 * </ul>
 * Stock: the simulator never changes stock per order, so SmartOmni deducts on a new non-cancelled order and restocks
 * once on a cancel before courier handover.
 */
@Component
@RequiredArgsConstructor
public class ShopeeOrderPolicy implements MarketplaceOrderPolicy {

    private final ShopeeReturnHandler returnHandler;

    @Override
    public Order.Platform platform() {
        return Order.Platform.SHOPEE;
    }

    @Override
    public OrderStatus mapStatus(String rawPlatformStatus) {
        return ShopeeOrderStatusMapper.map(rawPlatformStatus);
    }

    @Override
    public boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return switch (from) {
            case PENDING -> OrderStatusFlow.isForward(from, to) || to == OrderStatus.CANCELLED;
            case TO_SHIP -> OrderStatusFlow.isForward(from, to) || to == OrderStatus.CANCELLED;
            case SHIPPED, DELIVERING -> OrderStatusFlow.isForward(from, to);
            case COMPLETED, CANCELLED, RETURNED, ON_HOLD -> false;
        };
    }

    @Override
    public boolean deductStockOnCreate(OrderStatus initialStatus) {
        return initialStatus != OrderStatus.CANCELLED;
    }

    @Override
    public boolean restockOnCancel(OrderStatus previousStatus, Order order) {
        if (order.getCourierScannedAt() != null) {
            return false;
        }
        return previousStatus == OrderStatus.PENDING || previousStatus == OrderStatus.TO_SHIP;
    }

    @Override
    public Order handleReturn(Long tenantId, Order order, ProcessReturnRequest request) {
        return returnHandler.handle(tenantId, order, request);
    }
}
