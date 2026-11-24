package com.smartomni.order.service;

import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.order.client.InventoryServiceClient;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderItem;
import com.smartomni.order.entity.OrderStatusHistory;
import com.smartomni.order.marketplace.MarketplaceOrderPolicyRegistry;
import com.smartomni.order.marketplace.shopee.ShopeeOrderPolicy;
import com.smartomni.order.marketplace.shopee.ShopeeReturnHandler;
import com.smartomni.order.marketplace.tiktok.TiktokOrderPolicy;
import com.smartomni.order.marketplace.tiktok.TiktokReturnHandler;
import com.smartomni.order.repository.OrderItemRepository;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.OrderStatusHistoryRepository;
import com.smartomni.order.repository.ReturnRefundItemRepository;
import com.smartomni.order.repository.ReturnRefundRepository;
import com.smartomni.order.repository.SellerWalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Per-marketplace order scenarios (plan section 7) run through {@link OrderService#processIncomingOrder} with an
 * in-memory order store, so the sequence of marketplace events is replayed as it would arrive from the queue.
 */
class MarketplaceOrderScenarioTest {

    private static final long TENANT = 1L;

    private final Map<String, Order> orders = new HashMap<>();
    private final List<OrderItem> items = new ArrayList<>();
    private final List<OrderStatusHistory> history = new ArrayList<>();
    private int ledgerRows;

    private InventoryServiceClient inventory;
    private OrderService service;

    @BeforeEach
    void setUp() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderItemRepository itemRepository = mock(OrderItemRepository.class);
        OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
        SellerWalletLedgerRepository ledgerRepository = mock(SellerWalletLedgerRepository.class);
        inventory = mock(InventoryServiceClient.class);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(eq(TENANT), any(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(orders.get(inv.<String>getArgument(2))));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId((long) orders.size() + 1);
            }
            orders.put(o.getPlatformOrderId(), o);
            return o;
        });
        when(itemRepository.save(any(OrderItem.class))).thenAnswer(inv -> {
            items.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(itemRepository.findByOrderId(anyLong())).thenAnswer(inv ->
                items.stream().filter(i -> i.getOrderId().equals(inv.getArgument(0))).toList());
        when(historyRepository.save(any(OrderStatusHistory.class))).thenAnswer(inv -> {
            history.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(ledgerRepository.existsByOrderId(anyLong())).thenAnswer(inv -> ledgerRows > 0);
        when(ledgerRepository.save(any())).thenAnswer(inv -> {
            ledgerRows++;
            return inv.getArgument(0);
        });

        ReturnRefundRepository refunds = mock(ReturnRefundRepository.class);
        ReturnRefundItemRepository refundItems = mock(ReturnRefundItemRepository.class);
        service = new OrderService(orderRepository, itemRepository, historyRepository, ledgerRepository, inventory,
                new MarketplaceOrderPolicyRegistry(List.of(
                        new ShopeeOrderPolicy(new ShopeeReturnHandler(orderRepository, refunds, refundItems)),
                        new TiktokOrderPolicy(new TiktokReturnHandler(refunds, refundItems, inventory)))));
    }

    private void receive(String platform, String orderId, String rawStatus, long updateTime) {
        service.processIncomingOrder(WebhookOrderPayload.builder()
                .tenantId(TENANT).platform(platform).platformOrderId(orderId)
                .orderStatus(rawStatus).platformUpdateTime(updateTime)
                .totalAmount(new BigDecimal("100000"))
                .items(List.of(WebhookOrderPayload.OrderItemPayload.builder().skuId(7L).quantity(2).build()))
                .build(), Order.OrderSource.WEBHOOK);
    }

    private Order order(String id) {
        return orders.get(id);
    }

    @Nested
    @DisplayName("Shopee (shopee-simulator @ 4ce7c84)")
    class Shopee {

        private void shopee(String id, String status, long time) {
            receive("SHOPEE", id, status, time);
        }

        @Test
        void happyPathDeductsOnceAndRecordsWalletOnce() {
            shopee("S1", "READY_TO_SHIP", 100);
            shopee("S1", "PROCESSED", 110);
            shopee("S1", "SHIPPED", 120);
            shopee("S1", "COMPLETED", 130);
            shopee("S1", "COMPLETED", 130); // webhook retry

            assertEquals(Order.OrderStatus.COMPLETED, order("S1").getStatus());
            // READY_TO_SHIP and PROCESSED are both TO_SHIP: no history row for PROCESSED
            assertEquals(List.of(Order.OrderStatus.TO_SHIP, Order.OrderStatus.SHIPPED, Order.OrderStatus.COMPLETED),
                    history.stream().map(OrderStatusHistory::getStatus).toList());
            assertEquals(1, ledgerRows);
            verify(inventory, times(1)).deductStock(7L, 2);
            verify(inventory, never()).restock(anyLong(), anyInt());
        }

        @Test
        void inCancelRejectedGoesBackToShipWithoutRestock() {
            shopee("S2", "PROCESSED", 100);
            shopee("S2", "IN_CANCEL", 110);
            assertEquals(Order.OrderStatus.ON_HOLD, order("S2").getStatus(), "M6: IN_CANCEL is not CANCELLED");
            shopee("S2", "READY_TO_SHIP", 120);

            assertEquals(Order.OrderStatus.TO_SHIP, order("S2").getStatus());
            verify(inventory, never()).restock(anyLong(), anyInt());
        }

        @Test
        void inCancelAcceptedRestocksOnce() {
            shopee("S3", "PROCESSED", 100);
            shopee("S3", "IN_CANCEL", 110);
            shopee("S3", "CANCELLED", 120);
            shopee("S3", "CANCELLED", 120);

            assertEquals(Order.OrderStatus.CANCELLED, order("S3").getStatus());
            verify(inventory, times(1)).restock(7L, 2);
        }

        @Test
        void toReturnOnCompletedOrderChangesNothing() {
            shopee("S4", "COMPLETED", 100);
            int historyBefore = history.size();
            shopee("S4", "TO_RETURN", 110);

            assertEquals(Order.OrderStatus.COMPLETED, order("S4").getStatus(), "M7: TO_RETURN is not RETURNED");
            assertEquals(historyBefore, history.size());
            assertEquals(1, ledgerRows);
        }

        @Test
        void returnedOrderIgnoresToReturnAndCompleted() {
            shopee("S5", "COMPLETED", 100);
            order("S5").setStatus(Order.OrderStatus.RETURNED); // WF-05 inspection passed
            int historyBefore = history.size();

            assertDoesNotThrow(() -> shopee("S5", "TO_RETURN", 110));
            assertDoesNotThrow(() -> shopee("S5", "COMPLETED", 120));

            assertEquals(Order.OrderStatus.RETURNED, order("S5").getStatus());
            assertNull(order("S5").getReconciliationReason());
            assertEquals(historyBefore, history.size());
        }

        @Test
        void newCancelledOrderDoesNotDeductStock() {
            shopee("S6", "CANCELLED", 100);

            assertEquals(Order.OrderStatus.CANCELLED, order("S6").getStatus());
            verify(inventory, never()).deductStock(anyLong(), anyInt());
        }

        @Test
        void staleEventIsIgnored() {
            shopee("S7", "READY_TO_SHIP", 100);
            shopee("S7", "SHIPPED", 200);
            shopee("S7", "READY_TO_SHIP", 150); // code 3 and code 4 can arrive out of order

            assertEquals(Order.OrderStatus.SHIPPED, order("S7").getStatus());
            assertNull(order("S7").getReconciliationReason());
        }

        @Test
        void cancelAfterShippedIsSkippedAndFlagged() {
            shopee("S8", "SHIPPED", 100);
            assertDoesNotThrow(() -> shopee("S8", "CANCELLED", 110));

            assertEquals(Order.OrderStatus.SHIPPED, order("S8").getStatus());
            assertEquals(OrderService.RECONCILIATION_STATUS_CONFLICT, order("S8").getReconciliationReason());
            verify(inventory, never()).restock(anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("TikTok (Smartomni-Tiktok-Simulator @ a830fce)")
    class Tiktok {

        private void tiktok(String id, String status, long time) {
            receive("TIKTOK_SHOP", id, status, time);
        }

        @Test
        void deliveredIsNotCompleted() {
            tiktok("T1", "AWAITING_SHIPMENT", 100);
            tiktok("T1", "AWAITING_COLLECTION", 110);
            tiktok("T1", "IN_TRANSIT", 120);
            tiktok("T1", "DELIVERED", 130);

            assertEquals(Order.OrderStatus.DELIVERING, order("T1").getStatus());
            assertEquals(0, ledgerRows, "M3: no wallet entry at DELIVERED");
            assertNull(order("T1").getCompletedAt());
            assertNull(order("T1").getEligibleForPointsAt());

            tiktok("T1", "COMPLETED", 1_700_000_000L);

            assertEquals(Order.OrderStatus.COMPLETED, order("T1").getStatus());
            assertEquals(1, ledgerRows);
            assertEquals(Instant.ofEpochSecond(1_700_000_000L), order("T1").getCompletedAt(), "phase-09: update_time");
            assertEquals(Instant.ofEpochSecond(1_700_000_000L).plusSeconds(15 * 86_400L),
                    order("T1").getEligibleForPointsAt());
            verifyNoInteractions(inventory);
        }

        @Test
        void skipFromAwaitingShipmentToCompletedIsAccepted() {
            tiktok("T2", "AWAITING_SHIPMENT", 100);
            tiktok("T2", "COMPLETED", 200); // intermediate webhooks lost

            assertEquals(Order.OrderStatus.COMPLETED, order("T2").getStatus());
            assertEquals(1, ledgerRows);
        }

        @Test
        void cancelAfterCollectionArrangedIsSkipped() {
            tiktok("T3", "AWAITING_COLLECTION", 100);
            assertDoesNotThrow(() -> tiktok("T3", "CANCELLED", 110));

            assertEquals(Order.OrderStatus.SHIPPED, order("T3").getStatus());
            assertEquals(OrderService.RECONCILIATION_STATUS_CONFLICT, order("T3").getReconciliationReason());
        }

        @Test
        void cancelBeforeShipmentNeverTouchesStock() {
            tiktok("T4", "UNPAID", 100);
            tiktok("T4", "CANCELLED", 110);

            assertEquals(Order.OrderStatus.CANCELLED, order("T4").getStatus());
            verifyNoInteractions(inventory);
        }
    }
}
