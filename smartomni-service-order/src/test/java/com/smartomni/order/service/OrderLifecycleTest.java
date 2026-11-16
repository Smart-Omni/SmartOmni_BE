package com.smartomni.order.service;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.client.InventoryServiceClient;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderItem;
import com.smartomni.order.entity.OrderStatusHistory;
import com.smartomni.order.entity.SellerWalletLedger;
import com.smartomni.order.repository.OrderItemRepository;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.OrderStatusHistoryRepository;
import com.smartomni.order.repository.ReturnRefundItemRepository;
import com.smartomni.order.repository.ReturnRefundRepository;
import com.smartomni.order.repository.SellerWalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Order Lifecycle & State Machine Unit Tests")
class OrderLifecycleTest {

    private static final Long TENANT_ID = 1L;
    private static final Long ORDER_ID = 100L;
    private static final String PLATFORM_ORDER_ID = "TT-ORDER-8888";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private SellerWalletLedgerRepository sellerWalletLedgerRepository;

    @Mock
    private InventoryServiceClient inventoryServiceClient;

    @Mock
    private ReturnRefundRepository returnRefundRepository;

    @Mock
    private ReturnRefundItemRepository returnRefundItemRepository;

    @InjectMocks
    private OrderService orderService;

    private Order sampleOrder;

    @AfterEach void clearTenantContext() { TenantContext.clear(); }

    @Test
    void updateStatusCannotTouchAnotherTenantOrder() {
        TenantContext.setTenantId(2L);
        when(orderRepository.findByIdAndTenantId(ORDER_ID, 2L)).thenReturn(Optional.empty());

        assertThrows(com.smartomni.common.exception.ResourceNotFoundException.class,
                () -> orderService.updateStatus(ORDER_ID, Order.OrderStatus.SHIPPED));

        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderStatusHistoryRepository, sellerWalletLedgerRepository, inventoryServiceClient);
    }

    @Test
    void updateStatusCanFindOwnTenantOrder() {
        TenantContext.setTenantId(TENANT_ID);
        sampleOrder.setStatus(Order.OrderStatus.PENDING);
        when(orderRepository.findByIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(sampleOrder)).thenReturn(sampleOrder);

        orderService.updateStatus(ORDER_ID, Order.OrderStatus.TO_SHIP);

        verify(orderRepository).save(sampleOrder);
    }

    @Test
    void manualStatusChangeIsRecordedWithStaffSource() {
        TenantContext.setTenantId(TENANT_ID);
        sampleOrder.setStatus(Order.OrderStatus.PENDING);
        when(orderRepository.findByIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(sampleOrder)).thenReturn(sampleOrder);

        orderService.updateStatus(ORDER_ID, Order.OrderStatus.TO_SHIP);

        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orderStatusHistoryRepository).save(history.capture());
        assertEquals(OrderStatusHistory.OrderChangeSource.STAFF, history.getValue().getSource());
    }

    @Test
    void staffCannotMoveToShipOrderToShippedManually() {
        TenantContext.setTenantId(TENANT_ID);
        sampleOrder.setStatus(Order.OrderStatus.TO_SHIP);
        when(orderRepository.findByIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(Optional.of(sampleOrder));

        var error = assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.updateStatus(ORDER_ID, Order.OrderStatus.SHIPPED));

        assertEquals("INVALID_ORDER_STATUS_TRANSITION", error.getErrorCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void staffCannotForceCompletedOrTriggerSideEffects() {
        TenantContext.setTenantId(TENANT_ID);
        sampleOrder.setStatus(Order.OrderStatus.PENDING);
        when(orderRepository.findByIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(Optional.of(sampleOrder));

        var error = assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.updateStatus(ORDER_ID, Order.OrderStatus.COMPLETED));

        assertEquals("INVALID_ORDER_STATUS_TRANSITION", error.getErrorCode());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderStatusHistoryRepository, sellerWalletLedgerRepository, inventoryServiceClient);
    }

    @Test
    void trackingRequestCannotClaimAnotherTenant() {
        var request = com.smartomni.common.dto.UpdateOrderTrackingRequest.builder()
                .tenantId(2L).platform("TIKTOK_SHOP").platformOrderId(PLATFORM_ORDER_ID).build();

        assertThrows(com.smartomni.common.exception.ResourceNotFoundException.class,
                () -> orderService.updateOrderTracking(request));
        verifyNoInteractions(orderRepository, inventoryServiceClient);
    }

    @Test
    void returnRequestCannotClaimAnotherTenant() {
        var request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(2L).platform("TIKTOK_SHOP").platformOrderId(PLATFORM_ORDER_ID).build();

        assertThrows(com.smartomni.common.exception.ResourceNotFoundException.class,
                () -> orderService.handleOrderReturn(request));
        verifyNoInteractions(orderRepository, inventoryServiceClient);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        sampleOrder = new Order();
        sampleOrder.setId(ORDER_ID);
        sampleOrder.setTenantId(TENANT_ID);
        sampleOrder.setPlatform(Order.Platform.TIKTOK_SHOP);
        sampleOrder.setPlatformOrderId(PLATFORM_ORDER_ID);
        sampleOrder.setStatus(Order.OrderStatus.TO_SHIP);
        sampleOrder.setTotalAmount(new BigDecimal("200000.00"));
    }

    @Test
    @DisplayName("Tạo đơn hàng mới thành công, ghi order_status_history ban đầu và trừ kho")
    void processIncomingOrder_newOrder_recordsStatusHistoryAndDeductsStock() {
        WebhookOrderPayload.OrderItemPayload itemPayload = WebhookOrderPayload.OrderItemPayload.builder()
                .skuId(101L)
                .quantity(2)
                .unitPrice(new BigDecimal("100000.00"))
                .build();

        WebhookOrderPayload payload = WebhookOrderPayload.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .orderStatus("AWAITING_SHIPMENT")
                .customerName("Nguyen Van A")
                .customerPhone("0901234567")
                .shippingAddress("123 Ha Noi")
                .totalAmount(new BigDecimal("200000.00"))
                .items(List.of(itemPayload))
                .build();

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.empty());

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(ORDER_ID);
            return o;
        });

        orderService.processIncomingOrder(payload, Order.OrderSource.WEBHOOK);

        // Verify order saved
        verify(orderRepository).save(any(Order.class));

        // Verify order_status_history recorded
        ArgumentCaptor<OrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertEquals(Order.OrderStatus.TO_SHIP, historyCaptor.getValue().getStatus());
        assertEquals(OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK, historyCaptor.getValue().getSource());

        // Verify inventory deducted
        verify(inventoryServiceClient).deductStock(eq(101L), eq(2));
    }

    @Test
    @DisplayName("Đơn hàng chuyển sang CANCELLED trước khi giao bưu tá: tự động hoàn trả tồn kho và ghi lịch sử")
    void handleOrderStatusChange_cancelledBeforeCourier_restocksInventory() {
        // Đơn hàng đang ở TO_SHIP và chưa quét bưu tá (courierScannedAt == null)
        sampleOrder.setStatus(Order.OrderStatus.TO_SHIP);
        sampleOrder.setCourierScannedAt(null);

        OrderItem item1 = new OrderItem();
        item1.setOrderId(ORDER_ID);
        item1.setSkuId(101L);
        item1.setQuantity(3);

        OrderItem item2 = new OrderItem();
        item2.setOrderId(ORDER_ID);
        item2.setSkuId(102L);
        item2.setQuantity(1);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);
        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(item1, item2));

        Order updated = orderService.handleOrderStatusChange(TENANT_ID, "TIKTOK_SHOP", PLATFORM_ORDER_ID, "CANCELLED");

        assertEquals(Order.OrderStatus.CANCELLED, updated.getStatus());

        // Verify ghi lịch sử
        ArgumentCaptor<OrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertEquals(Order.OrderStatus.CANCELLED, historyCaptor.getValue().getStatus());
        assertEquals(OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK, historyCaptor.getValue().getSource());

        // Verify hoàn tồn kho cho từng SKU
        verify(inventoryServiceClient).restock(eq(101L), eq(3));
        verify(inventoryServiceClient).restock(eq(102L), eq(1));
    }

    @Test
    @DisplayName("Từ chối hủy đơn đã giao bưu tá và không hoàn kho")
    void handleOrderStatusChange_cancelledAfterCourier_doesNotRestock() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);
        sampleOrder.setCourierScannedAt(Instant.now().minusSeconds(3600));

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.handleOrderStatusChange(TENANT_ID, "TIKTOK_SHOP", PLATFORM_ORDER_ID, "CANCELLED"));

        assertEquals(Order.OrderStatus.SHIPPED, sampleOrder.getStatus());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(orderStatusHistoryRepository, sellerWalletLedgerRepository);
        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Đơn hàng chuyển sang COMPLETED: ghi nhận doanh thu vào seller_wallet_ledger và ghi lịch sử")
    void handleOrderStatusChange_completed_recordsWalletLedger() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);
        sampleOrder.setTotalAmount(new BigDecimal("500000.00"));

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);
        when(sellerWalletLedgerRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        Order updated = orderService.handleOrderStatusChange(TENANT_ID, "TIKTOK_SHOP", PLATFORM_ORDER_ID, "COMPLETED");

        assertEquals(Order.OrderStatus.COMPLETED, updated.getStatus());

        // Verify ghi lịch sử
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));

        // Verify ghi seller_wallet_ledger
        ArgumentCaptor<SellerWalletLedger> ledgerCaptor = ArgumentCaptor.forClass(SellerWalletLedger.class);
        verify(sellerWalletLedgerRepository).save(ledgerCaptor.capture());

        SellerWalletLedger ledger = ledgerCaptor.getValue();
        assertEquals(TENANT_ID, ledger.getTenantId());
        assertEquals(ORDER_ID, ledger.getOrderId());
        assertEquals(new BigDecimal("500000.00"), ledger.getGrossAmount());
        assertEquals(new BigDecimal("25000.0000"), ledger.getPlatformFee()); // 5% of 500,000 = 25,000
        assertEquals(new BigDecimal("475000.0000"), ledger.getNetPayout()); // 500,000 - 25,000 = 475,000
        assertNotNull(ledger.getRecordedAt());
    }

    @Test
    @DisplayName("Không tạo trùng seller_wallet_ledger nếu đã tồn tại cho orderId")
    void handleOrderStatusChange_completed_duplicateLedgerPrevented() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);
        when(sellerWalletLedgerRepository.existsByOrderId(ORDER_ID)).thenReturn(true);

        orderService.handleOrderStatusChange(TENANT_ID, "TIKTOK_SHOP", PLATFORM_ORDER_ID, "DELIVERED");

        verify(sellerWalletLedgerRepository, never()).save(any(SellerWalletLedger.class));
    }

    @Test
    @DisplayName("updateOrderTracking: Cập nhật tracking_number, shipping_provider và chuyển trạng thái SHIPPED khi IN_TRANSIT")
    void updateOrderTracking_inTransit_updatesTrackingAndTransitionsToShipped() {
        sampleOrder.setStatus(Order.OrderStatus.TO_SHIP);
        sampleOrder.setTrackingNumber(null);
        sampleOrder.setCourierScannedAt(null);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Instant scannedTime = Instant.parse("2026-03-25T10:00:00Z");
        com.smartomni.common.dto.UpdateOrderTrackingRequest request = com.smartomni.common.dto.UpdateOrderTrackingRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .trackingNumber("TRK-VN-8888")
                .shippingProvider("J&T Express")
                .packageStatus("IN_TRANSIT")
                .courierScannedAt(scannedTime)
                .build();

        Order updated = orderService.updateOrderTracking(request);

        assertNotNull(updated);
        assertEquals("TRK-VN-8888", updated.getTrackingNumber());
        assertEquals("J&T Express", updated.getShippingMethod());
        assertEquals(scannedTime, updated.getCourierScannedAt());
        assertEquals(Order.OrderStatus.SHIPPED, updated.getStatus());

        // Verify ghi lịch sử đổi trạng thái sang SHIPPED
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    @DisplayName("updateOrderTracking: Trạng thái PACKED chỉ cập nhật mã vận đơn, chưa cập nhật courierScannedAt")
    void updateOrderTracking_packed_updatesTrackingOnly() {
        sampleOrder.setStatus(Order.OrderStatus.TO_SHIP);
        sampleOrder.setTrackingNumber(null);
        sampleOrder.setCourierScannedAt(null);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.UpdateOrderTrackingRequest request = com.smartomni.common.dto.UpdateOrderTrackingRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .trackingNumber("TRK-VN-9999")
                .shippingProvider("SPX Express")
                .packageStatus("PACKED")
                .build();

        Order updated = orderService.updateOrderTracking(request);

        assertNotNull(updated);
        assertEquals("TRK-VN-9999", updated.getTrackingNumber());
        assertEquals("SPX Express", updated.getShippingMethod());
        assertNull(updated.getCourierScannedAt());
        assertEquals(Order.OrderStatus.TO_SHIP, updated.getStatus());
    }

    @Test
    @DisplayName("handleOrderReturn: Chấp nhận trả hàng thành công -> chuyển sang RETURNED và hoàn trả tồn kho")
    void handleOrderReturn_completed_withRestock() {
        sampleOrder.setStatus(Order.OrderStatus.COMPLETED);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderItem item1 = new OrderItem();
        item1.setOrderId(ORDER_ID);
        item1.setSkuId(101L);
        item1.setQuantity(2);

        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(item1));
        when(inventoryServiceClient.restock(eq(101L), eq(2))).thenReturn(ApiResponse.success(null));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .returnId("ret-888")
                .returnStatus("RETURN_COMPLETED")
                .restockRequired(true)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.RETURNED, updated.getStatus());

        // Verify ghi nhận lịch sử đổi trạng thái
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));

        // Verify hoàn tồn kho
        verify(inventoryServiceClient, times(1)).restock(101L, 2);
    }

    @Test
    @DisplayName("handleOrderReturn: Yêu cầu trả hàng đang xử lý -> chuyển sang ON_HOLD và không hoàn kho")
    void handleOrderReturn_requested_withoutRestock() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .returnId("ret-999")
                .returnStatus("RETURN_REQUESTED")
                .restockRequired(false)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.ON_HOLD, updated.getStatus());

        // Verify không hoàn kho
        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("handleOrderReturn: RECEIVE_APPROVE + restockRequired = true -> RETURNED và gọi restock")
    void handleOrderReturn_receiveApprove_restocksInventory() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderItem item1 = new OrderItem();
        item1.setSkuId(101L);
        item1.setQuantity(2);

        when(orderItemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(item1));
        when(inventoryServiceClient.restock(eq(101L), eq(2))).thenReturn(ApiResponse.success(null));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .returnId("ret-approve-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.RECEIVE_APPROVE)
                .returnStatus("RETURN_COMPLETED")
                .restockRequired(true)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.RETURNED, updated.getStatus());
        assertEquals("RESTOCKED", updated.getRestockStatus());
        verify(inventoryServiceClient, times(1)).restock(101L, 2);
    }

    @Test
    @DisplayName("handleOrderReturn: REFUND_ONLY -> RETURNED và NGHIÊM CẤM gọi restock")
    void handleOrderReturn_refundOnly_strictlyNeverRestocks() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .returnId("ret-refund-only")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.REFUND_ONLY)
                .returnType("REFUND_ONLY")
                .returnStatus("REFUND_COMPLETE")
                .restockRequired(false)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.RETURNED, updated.getStatus());
        assertEquals("NO_RESTOCK_REFUND_ONLY", updated.getRestockStatus());
        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("handleOrderReturn: RECEIVE_REJECT -> ON_HOLD, lưu rejectReason & evidenceUrls và NGHIÊM CẤM restock")
    void handleOrderReturn_receiveRejected_strictlyNeverRestocks() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .returnId("ret-reject-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.RECEIVE_REJECT)
                .returnStatus("SELLER_REJECTED")
                .rejectReason("Hàng bị bể vỡ do va đập, không đúng nguyên trạng")
                .evidenceUrls(List.of("https://cdn.smartomni.com/ev1.jpg", "https://cdn.smartomni.com/ev2.jpg"))
                .restockRequired(false)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.ON_HOLD, updated.getStatus());
        assertEquals("REJECTED", updated.getRestockStatus());
        assertEquals("Hàng bị bể vỡ do va đập, không đúng nguyên trạng", updated.getRejectReason());
        assertTrue(updated.getEvidenceUrls().contains("https://cdn.smartomni.com/ev1.jpg"));
        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("handleOrderReturn: DISPUTE_CLAIM -> ON_HOLD và NGHIÊM CẤM gọi restock")
    void handleOrderReturn_disputeClaim_strictlyNeverRestocks() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .returnId("ret-dispute-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.DISPUTE_CLAIM)
                .returnStatus("DISPUTE_IN_PROGRESS")
                .restockRequired(false)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.ON_HOLD, updated.getStatus());
        assertEquals("DISPUTED", updated.getRestockStatus());
        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("handleOrderReturn: RECEIVE_APPROVE gọi lặp lại không cộng kho lần 2 (idempotent)")
    void handleOrderReturn_duplicateReceiveApprove_isIdempotentAndNeverRestocksTwice() {
        sampleOrder.setStatus(Order.OrderStatus.RETURNED);
        sampleOrder.setRestockStatus("RESTOCKED");

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.SHOPEE, "SP-ORDER-9999"))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("SHOPEE")
                .platformOrderId("SP-ORDER-9999")
                .returnId("ret-duplicate-shopee")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.RECEIVE_APPROVE)
                .returnStatus("ACCEPTED")
                .restockRequired(true)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.RETURNED, updated.getStatus());
        assertEquals("RESTOCKED", updated.getRestockStatus());
        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("handleOrderReturn: trả hàng 1 phần chỉ cộng kho đúng các SKU trong return items")
    void handleOrderReturn_partialItemReturn_restocksOnlyReturnedItems() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);
        sampleOrder.setRestockStatus(null);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.SHOPEE, "SP-ORDER-9999"))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.ProcessReturnRequest.ReturnItemDto returnItem =
                com.smartomni.common.dto.ProcessReturnRequest.ReturnItemDto.builder()
                        .skuId(101L)
                        .quantity(1)
                        .name("Áo thun đen")
                        .build();

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .platform("SHOPEE")
                .platformOrderId("SP-ORDER-9999")
                .returnId("ret-partial-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.RECEIVE_APPROVE)
                .returnStatus("ACCEPTED")
                .restockRequired(true)
                .items(List.of(returnItem))
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.RETURNED, updated.getStatus());
        assertEquals("RESTOCKED", updated.getRestockStatus());
        verify(inventoryServiceClient, times(1)).restock(101L, 1);
        verify(inventoryServiceClient, never()).restock(eq(102L), anyInt());
    }

    @Test
    @DisplayName("handleOrderReturn: Shopee trả hàng vật lý AWAITING_INSPECTION chuyển ON_HOLD và không cộng kho")
    void handleOrderReturn_awaitingInspection_setsOnHoldAndDoesNotRestock() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);
        sampleOrder.setRestockStatus(null);
        sampleOrder.setConnectionId(88L);

        when(orderRepository.findByTenantIdAndConnectionIdAndPlatformOrderId(TENANT_ID, 88L, "SP-ORDER-1234"))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.smartomni.common.dto.ProcessReturnRequest request = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID)
                .connectionId(88L)
                .platform("SHOPEE")
                .platformOrderId("SP-ORDER-1234")
                .returnId("ret-shopee-awaiting-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.AWAITING_INSPECTION)
                .returnStatus("ACCEPTED")
                .returnType("PHYSICAL_RETURN")
                .returnReason("Damaged during shipping")
                .restockRequired(false)
                .build();

        Order updated = orderService.handleOrderReturn(request);

        assertNotNull(updated);
        assertEquals(Order.OrderStatus.ON_HOLD, updated.getStatus());
        assertEquals("AWAITING_INSPECTION", updated.getRestockStatus());
        verify(returnRefundRepository, times(1)).save(any());
        verifyNoInteractions(inventoryServiceClient);
    }

    @Test
    @DisplayName("getOrderDetail returns OrderDetailDto with order items")
    void getOrderDetail_Success() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setTenantId(TENANT_ID);
        order.setPlatform(Order.Platform.SHOPEE);
        order.setPlatformOrderId("SP-ORDER-100");
        order.setStatus(Order.OrderStatus.PENDING);

        OrderItem item = new OrderItem();
        item.setId(1L);
        item.setOrderId(ORDER_ID);
        item.setTenantId(TENANT_ID);
        item.setSkuId(55L);
        item.setQuantity(3);
        item.setUnitPrice(BigDecimal.valueOf(100000));

        when(orderRepository.findByIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(List.of(item));

        com.smartomni.common.dto.OrderDetailDto detail = orderService.getOrderDetail(ORDER_ID);

        assertNotNull(detail);
        assertEquals(ORDER_ID, detail.getId());
        assertEquals("SHOPEE", detail.getPlatform());
        assertEquals(1, detail.getItems().size());
        assertEquals(55L, detail.getItems().get(0).getSkuId());
        assertEquals(3, detail.getItems().get(0).getQuantity());
    }

    @Test
    @DisplayName("createManualOrder creates order and line items successfully")
    void createManualOrder_Success() {
        com.smartomni.common.dto.CreateManualOrderRequest request = com.smartomni.common.dto.CreateManualOrderRequest.builder()
                .platform("SHOPEE")
                .customerName("Tran Thi B")
                .items(List.of(new com.smartomni.common.dto.CreateManualOrderRequest.ManualOrderItem(55L, 2, BigDecimal.valueOf(100000), null)))
                .build();

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId(ORDER_ID);
            return o;
        });
        when(orderRepository.findByIdAndTenantId(ORDER_ID, TENANT_ID)).thenAnswer(invocation -> {
            Order o = new Order();
            o.setId(ORDER_ID);
            o.setTenantId(TENANT_ID);
            o.setPlatform(Order.Platform.SHOPEE);
            o.setCustomerName("Tran Thi B");
            o.setStatus(Order.OrderStatus.PENDING);
            return Optional.of(o);
        });
        when(orderItemRepository.findByOrderIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(List.of());

        com.smartomni.common.dto.OrderDetailDto result = orderService.createManualOrder(request);

        assertNotNull(result);
        assertEquals(ORDER_ID, result.getId());
        assertEquals("Tran Thi B", result.getCustomerName());
        verify(orderRepository).save(any(Order.class));
        verify(orderItemRepository).save(any(OrderItem.class));
    }
}


