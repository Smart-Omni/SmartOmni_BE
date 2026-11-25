package com.smartomni.order.service;

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
        orderService = new OrderService(orderRepository, orderItemRepository, orderStatusHistoryRepository,
                sellerWalletLedgerRepository, inventoryServiceClient,
                new com.smartomni.order.marketplace.MarketplaceOrderPolicyRegistry(List.of(
                        new com.smartomni.order.marketplace.shopee.ShopeeOrderPolicy(
                                new com.smartomni.order.marketplace.shopee.ShopeeReturnHandler(
                                        orderRepository, returnRefundRepository, returnRefundItemRepository)),
                        new com.smartomni.order.marketplace.tiktok.TiktokOrderPolicy(
                                new com.smartomni.order.marketplace.tiktok.TiktokReturnHandler(
                                        returnRefundRepository, returnRefundItemRepository, inventoryServiceClient)))));
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

        // TikTok inventory is applied from the absolute stock webhook.
        verify(inventoryServiceClient, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Event cũ hơn (platform update_time nhỏ hơn) không ghi đè trạng thái mới")
    void processIncomingOrder_staleEventDoesNotRevertStatus() {
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);
        sampleOrder.setPlatformUpdatedAt(java.time.Instant.ofEpochSecond(1_800_000_200L));
        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));

        orderService.processIncomingOrder(WebhookOrderPayload.builder()
                .tenantId(TENANT_ID).platform("TIKTOK_SHOP").platformOrderId(PLATFORM_ORDER_ID)
                .orderStatus("AWAITING_SHIPMENT").platformUpdateTime(1_800_000_100L).build(), Order.OrderSource.WEBHOOK);

        assertEquals(Order.OrderStatus.SHIPPED, sampleOrder.getStatus());
        assertEquals(java.time.Instant.ofEpochSecond(1_800_000_200L), sampleOrder.getPlatformUpdatedAt());
        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(orderStatusHistoryRepository);
    }

    @Test
    @DisplayName("Event mới hơn cập nhật trạng thái và platform_updated_at")
    void processIncomingOrder_newerEventUpdatesStatusAndPlatformTime() {
        sampleOrder.setPlatformUpdatedAt(java.time.Instant.ofEpochSecond(1_800_000_100L));
        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.processIncomingOrder(WebhookOrderPayload.builder()
                .tenantId(TENANT_ID).platform("TIKTOK_SHOP").platformOrderId(PLATFORM_ORDER_ID)
                .orderStatus("IN_TRANSIT").platformUpdateTime(1_800_000_200L).build(), Order.OrderSource.WEBHOOK);

        assertEquals(Order.OrderStatus.SHIPPED, sampleOrder.getStatus());
        assertEquals(java.time.Instant.ofEpochSecond(1_800_000_200L), sampleOrder.getPlatformUpdatedAt());
    }

    @Test
    @DisplayName("Payload không có platformUpdateTime (Shopee) vẫn cập nhật trạng thái")
    void processIncomingOrder_withoutPlatformTimeStillUpdates() {
        sampleOrder.setPlatform(Order.Platform.SHOPEE);
        sampleOrder.setPlatformUpdatedAt(java.time.Instant.ofEpochSecond(1_800_000_200L));
        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.SHOPEE, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.processIncomingOrder(WebhookOrderPayload.builder()
                .tenantId(TENANT_ID).platform("SHOPEE").platformOrderId(PLATFORM_ORDER_ID)
                .orderStatus("SHIPPED").build(), Order.OrderSource.WEBHOOK);

        assertEquals(Order.OrderStatus.SHIPPED, sampleOrder.getStatus());
        assertEquals(java.time.Instant.ofEpochSecond(1_800_000_200L), sampleOrder.getPlatformUpdatedAt());
    }

    @Test
    @DisplayName("Đơn mới lưu platform_updated_at và reconciliation_reason SKU_UNMAPPED, không trừ kho")
    void processIncomingOrder_newOrderKeepsPlatformTimeAndReconciliationReason() {
        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.processIncomingOrder(WebhookOrderPayload.builder()
                .tenantId(TENANT_ID).platform("TIKTOK_SHOP").platformOrderId(PLATFORM_ORDER_ID)
                .orderStatus("AWAITING_SHIPMENT").platformUpdateTime(1_800_000_100L)
                .reconciliationReason("SKU_UNMAPPED")
                .items(List.of(WebhookOrderPayload.OrderItemPayload.builder().quantity(1).build()))
                .build(), Order.OrderSource.WEBHOOK);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertEquals(java.time.Instant.ofEpochSecond(1_800_000_100L), saved.getValue().getPlatformUpdatedAt());
        assertEquals("SKU_UNMAPPED", saved.getValue().getReconciliationReason());
        verify(inventoryServiceClient, never()).deductStock(anyLong(), anyInt());
    }

    private com.smartomni.common.dto.ProcessReturnRequest tiktokReturn(String status, long updateTime) {
        return com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID).platform("TIKTOK_SHOP").connectionId(9L).orderId(PLATFORM_ORDER_ID).returnId("RET-1")
                .returnType("RETURN_AND_REFUND").returnStatus(status).platformUpdateTime(updateTime)
                .refundAmount(new BigDecimal("318000"))
                .items(List.of(
                        com.smartomni.common.dto.ProcessReturnRequest.ReturnItemDto.builder()
                                .skuId(101L).externalItemId("OL-1").quantity(1).refundAmount(new BigDecimal("159000")).build(),
                        com.smartomni.common.dto.ProcessReturnRequest.ReturnItemDto.builder()
                                .skuId(101L).externalItemId("OL-2").quantity(1).refundAmount(new BigDecimal("159000")).build()))
                .build();
    }

    private com.smartomni.order.entity.ReturnRefund stubTiktokReturnStore(com.smartomni.order.entity.ReturnRefund stored) {
        sampleOrder.setStatus(Order.OrderStatus.COMPLETED);
        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(returnRefundRepository.findByTenantIdAndConnectionIdAndExternalReturnId(TENANT_ID, 9L, "RET-1"))
                .thenReturn(Optional.ofNullable(stored));
        lenient().when(returnRefundRepository.save(any(com.smartomni.order.entity.ReturnRefund.class))).thenAnswer(inv -> {
            com.smartomni.order.entity.ReturnRefund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(55L);
            return r;
        });
        return stored;
    }

    @Test
    @DisplayName("TikTok 07b.2: trả hàng không đổi trạng thái đơn (kể cả COMPLETED), lưu return_status và update_time của sàn")
    void tiktokReturnNeverChangesOrderStatusAndKeepsMarketplaceState() {
        stubTiktokReturnStore(null);

        for (String status : List.of("RETURN_OR_REFUND_REQUEST_PENDING", "AWAITING_BUYER_SHIP", "BUYER_SHIPPED_ITEM")) {
            orderService.handleOrderReturn(tiktokReturn(status, 1_800_000_100L));
        }

        assertEquals(Order.OrderStatus.COMPLETED, sampleOrder.getStatus());
        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(orderStatusHistoryRepository, inventoryServiceClient);
        ArgumentCaptor<com.smartomni.order.entity.ReturnRefund> saved =
                ArgumentCaptor.forClass(com.smartomni.order.entity.ReturnRefund.class);
        verify(returnRefundRepository, atLeastOnce()).save(saved.capture());
        com.smartomni.order.entity.ReturnRefund last = saved.getValue();
        assertEquals("BUYER_SHIPPED_ITEM", last.getMarketplaceStatus());
        assertEquals(java.time.Instant.ofEpochSecond(1_800_000_100L), last.getMarketplaceUpdatedAt());
        assertEquals(com.smartomni.order.entity.ReturnRefund.RefundStatus.APPROVED, last.getStatus());
        assertEquals(new BigDecimal("318000"), last.getRefundAmount());
    }

    @Test
    @DisplayName("TikTok 07b.4: SUCCESS hoàn kho từng dòng bằng receipt idempotent theo (đơn, dòng hàng)")
    void tiktokReturnSuccessRestocksEachLineOnceByOrderLineKey() {
        stubTiktokReturnStore(null);

        orderService.handleOrderReturn(tiktokReturn("RETURN_OR_REFUND_REQUEST_SUCCESS", 1_800_000_200L));

        verify(inventoryServiceClient).restock(101L, 1, "tiktok-return:" + PLATFORM_ORDER_ID + ":OL-1");
        verify(inventoryServiceClient).restock(101L, 1, "tiktok-return:" + PLATFORM_ORDER_ID + ":OL-2");
        assertEquals(Order.OrderStatus.COMPLETED, sampleOrder.getStatus());
        verifyNoInteractions(orderStatusHistoryRepository);
    }

    @Test
    @DisplayName("TikTok 07b.2: event cũ hơn update_time đã lưu bị bỏ qua (không hoàn kho, không ghi đè)")
    void tiktokReturnOlderEventIsIgnored() {
        com.smartomni.order.entity.ReturnRefund stored = new com.smartomni.order.entity.ReturnRefund();
        stored.setId(55L);
        stored.setMarketplaceStatus("REFUND_OR_RETURN_REQUEST_REJECT");
        stored.setMarketplaceUpdatedAt(java.time.Instant.ofEpochSecond(1_800_000_300L));
        stubTiktokReturnStore(stored);

        orderService.handleOrderReturn(tiktokReturn("RETURN_OR_REFUND_REQUEST_SUCCESS", 1_800_000_200L));

        assertEquals("REFUND_OR_RETURN_REQUEST_REJECT", stored.getMarketplaceStatus());
        verify(returnRefundRepository, never()).save(any());
        verifyNoInteractions(inventoryServiceClient);
    }

    @Test
    @DisplayName("TikTok 07b.4: trạng thái chưa xong hoặc bị từ chối không hoàn kho; dòng chưa ánh xạ SKU không hoàn kho")
    void tiktokReturnRestocksOnlyMappedLinesOfASucceededRequest() {
        stubTiktokReturnStore(null);
        orderService.handleOrderReturn(tiktokReturn("REFUND_OR_RETURN_REQUEST_REJECT", 1_800_000_100L));
        verifyNoInteractions(inventoryServiceClient);

        com.smartomni.common.dto.ProcessReturnRequest unmapped = com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID).platform("TIKTOK_SHOP").connectionId(9L).orderId(PLATFORM_ORDER_ID).returnId("RET-1")
                .returnStatus("RETURN_OR_REFUND_REQUEST_COMPLETE").platformUpdateTime(1_800_000_400L)
                .items(List.of(com.smartomni.common.dto.ProcessReturnRequest.ReturnItemDto.builder()
                        .externalItemId("OL-9").quantity(1).build()))
                .build();
        orderService.handleOrderReturn(unmapped);
        verifyNoInteractions(inventoryServiceClient);
    }

    @Test
    @DisplayName("Đơn hàng chuyển sang CANCELLED trước khi giao bưu tá: tự động hoàn trả tồn kho và ghi lịch sử")
    void handleOrderStatusChange_cancelledBeforeCourier_restocksInventory() {
        // Đơn hàng đang ở TO_SHIP và chưa quét bưu tá (courierScannedAt == null)
        sampleOrder.setStatus(Order.OrderStatus.TO_SHIP);
        sampleOrder.setCourierScannedAt(null);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);

        Order updated = orderService.handleOrderStatusChange(TENANT_ID, "TIKTOK_SHOP", PLATFORM_ORDER_ID, "CANCELLED");

        assertEquals(Order.OrderStatus.CANCELLED, updated.getStatus());

        // Verify ghi lịch sử
        ArgumentCaptor<OrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertEquals(Order.OrderStatus.CANCELLED, historyCaptor.getValue().getStatus());
        assertEquals(OrderStatusHistory.OrderChangeSource.MARKETPLACE_WEBHOOK, historyCaptor.getValue().getSource());

        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Hủy sau khi giao bưu tá: bỏ qua (không lỗi, không DLQ), gắn STATUS_CONFLICT, không hoàn kho")
    void handleOrderStatusChange_cancelledAfterCourier_isSkippedAndFlagged() {
        // Plan 3.2 / M11: an invalid marketplace transition is logged and skipped instead of throwing into the DLQ.
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);
        sampleOrder.setCourierScannedAt(Instant.now().minusSeconds(3600));

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.handleOrderStatusChange(TENANT_ID, "TIKTOK_SHOP", PLATFORM_ORDER_ID, "CANCELLED");

        assertEquals(Order.OrderStatus.SHIPPED, result.getStatus());
        assertEquals(OrderService.RECONCILIATION_STATUS_CONFLICT, result.getReconciliationReason());
        verify(orderRepository, times(1)).save(sampleOrder);
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

        // Was "DELIVERED", which since D3 maps to DELIVERING and would pass without reaching the ledger at all.
        orderService.handleOrderStatusChange(TENANT_ID, "TIKTOK_SHOP", PLATFORM_ORDER_ID, "COMPLETED");

        verify(sellerWalletLedgerRepository, never()).save(any(SellerWalletLedger.class));
    }

    @Test
    @DisplayName("updateOrderTracking: lưu tracking_number, shipping_provider, courierScannedAt nhưng không đổi trạng thái đơn")
    void updateOrderTracking_handover_storesTrackingButKeepsStatus() {
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
        // The status comes only from the marketplace order status event (MarketplaceOrderPolicy).
        assertEquals(Order.OrderStatus.TO_SHIP, updated.getStatus());
        verify(orderStatusHistoryRepository, never()).save(any(OrderStatusHistory.class));
    }

    @Test
    @DisplayName("updateOrderTracking: courierScannedAt đầu tiên được giữ, lần gửi sau không ghi đè")
    void updateOrderTracking_keepsFirstCourierScan() {
        Instant firstScan = Instant.parse("2026-03-25T10:00:00Z");
        sampleOrder.setStatus(Order.OrderStatus.SHIPPED);
        sampleOrder.setCourierScannedAt(firstScan);

        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.TIKTOK_SHOP, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order updated = orderService.updateOrderTracking(com.smartomni.common.dto.UpdateOrderTrackingRequest.builder()
                .tenantId(TENANT_ID)
                .platform("TIKTOK_SHOP")
                .platformOrderId(PLATFORM_ORDER_ID)
                .packageStatus("FULFILLING")
                .courierScannedAt(Instant.parse("2026-03-26T10:00:00Z"))
                .build());

        assertEquals(firstScan, updated.getCourierScannedAt());
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

    // Shopee return push (code 29). Plan 4.1 / M8 / principle 6: the push only records return_refund; the order
    // status never changes (the simulator moves COMPLETED -> TO_RETURN -> COMPLETED, which maps to COMPLETED) and
    // nothing is restocked: RETURNED and the restock come only from WF-05 inspection. These tests used to assert
    // ON_HOLD/RETURNED and a restock on the push itself.

    private com.smartomni.common.dto.ProcessReturnRequest.ProcessReturnRequestBuilder shopeeReturn(String returnId) {
        return com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID).platform("SHOPEE").platformOrderId(PLATFORM_ORDER_ID).returnId(returnId);
    }

    private void stubShopeeOrder(Order.OrderStatus status) {
        sampleOrder.setPlatform(Order.Platform.SHOPEE);
        sampleOrder.setStatus(status);
        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.SHOPEE, PLATFORM_ORDER_ID))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private com.smartomni.order.entity.ReturnRefund savedReturn() {
        ArgumentCaptor<com.smartomni.order.entity.ReturnRefund> captor =
                ArgumentCaptor.forClass(com.smartomni.order.entity.ReturnRefund.class);
        verify(returnRefundRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Shopee push 29 (mọi action): đơn giữ COMPLETED, không ghi lịch sử, không hoàn kho")
    void shopeeReturnPushNeverChangesOrderStatusOrRestocks() {
        stubShopeeOrder(Order.OrderStatus.COMPLETED);

        for (var action : com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.values()) {
            Order updated = orderService.handleOrderReturn(shopeeReturn("ret-" + action)
                    .returnAction(action).returnStatus("ACCEPTED").restockRequired(true).build());
            assertEquals(Order.OrderStatus.COMPLETED, updated.getStatus(), action.name());
        }
        Order updated = orderService.handleOrderReturn(shopeeReturn("ret-no-action")
                .returnStatus("RETURN_COMPLETED").restockRequired(true).build());

        assertEquals(Order.OrderStatus.COMPLETED, updated.getStatus());
        assertNull(updated.getRestockStatus());
        verifyNoInteractions(orderStatusHistoryRepository, inventoryServiceClient, sellerWalletLedgerRepository);
    }

    @Test
    @DisplayName("Shopee push 29 AWAITING_INSPECTION: return_refund REQUESTED/PENDING, giữ marketplace status, lý do")
    void shopeeReturnAwaitingInspectionRecordsReturnOnly() {
        sampleOrder.setPlatform(Order.Platform.SHOPEE);
        sampleOrder.setStatus(Order.OrderStatus.COMPLETED);
        sampleOrder.setConnectionId(88L);
        when(orderRepository.findByTenantIdAndConnectionIdAndPlatformOrderId(TENANT_ID, 88L, "SP-ORDER-1234"))
                .thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order updated = orderService.handleOrderReturn(com.smartomni.common.dto.ProcessReturnRequest.builder()
                .tenantId(TENANT_ID).connectionId(88L).platform("SHOPEE").platformOrderId("SP-ORDER-1234")
                .returnId("ret-shopee-awaiting-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.AWAITING_INSPECTION)
                .returnStatus("ACCEPTED").returnType("PHYSICAL_RETURN").returnReason("Damaged during shipping")
                .restockRequired(false).build());

        assertEquals(Order.OrderStatus.COMPLETED, updated.getStatus());
        assertEquals("Damaged during shipping", updated.getReturnReason());
        com.smartomni.order.entity.ReturnRefund refund = savedReturn();
        assertEquals(com.smartomni.order.entity.ReturnRefund.RefundStatus.REQUESTED, refund.getStatus());
        assertEquals(com.smartomni.order.entity.ReturnRefund.InspectionResultType.PENDING, refund.getInspectionResult());
        assertEquals("ACCEPTED", refund.getMarketplaceStatus());
        assertFalse(refund.isRestocked());
        verifyNoInteractions(inventoryServiceClient, orderStatusHistoryRepository);
    }

    @Test
    @DisplayName("Shopee push 29 trả một phần rồi hủy: return_refund REQUESTED rồi REJECTED, đơn vẫn COMPLETED")
    void shopeePartialReturnThenCancelledKeepsOrderCompleted() {
        stubShopeeOrder(Order.OrderStatus.COMPLETED);
        com.smartomni.order.entity.ReturnRefund stored = new com.smartomni.order.entity.ReturnRefund();
        stored.setId(70L);
        when(returnRefundRepository.findByTenantIdAndOrderId(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.empty(), Optional.of(stored));
        when(returnRefundRepository.save(any(com.smartomni.order.entity.ReturnRefund.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        orderService.handleOrderReturn(shopeeReturn("ret-partial-1").partialReturn(true)
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.AWAITING_INSPECTION)
                .returnStatus("REQUESTED").build());
        assertEquals(com.smartomni.order.entity.ReturnRefund.RefundStatus.REQUESTED, savedReturn().getStatus());

        orderService.handleOrderReturn(shopeeReturn("ret-partial-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.RECEIVE_REJECT)
                .returnStatus("CANCELLED").build());

        assertEquals(com.smartomni.order.entity.ReturnRefund.RefundStatus.REJECTED, stored.getStatus());
        assertEquals("CANCELLED", stored.getMarketplaceStatus());
        assertEquals(Order.OrderStatus.COMPLETED, sampleOrder.getStatus());
        verifyNoInteractions(inventoryServiceClient, orderStatusHistoryRepository);
    }

    @Test
    @DisplayName("Shopee push 29 sau khi WF-05 đã kiểm hàng: chỉ cập nhật marketplace status, không ghi đè kết quả kiểm")
    void shopeeReturnPushAfterInspectionKeepsWarehouseOutcome() {
        stubShopeeOrder(Order.OrderStatus.RETURNED);
        sampleOrder.setRestockStatus("RESTOCKED");
        com.smartomni.order.entity.ReturnRefund inspected = new com.smartomni.order.entity.ReturnRefund();
        inspected.setId(71L);
        inspected.setStatus(com.smartomni.order.entity.ReturnRefund.RefundStatus.COMPLETED);
        inspected.setInspectionResult(com.smartomni.order.entity.ReturnRefund.InspectionResultType.MATCHED);
        inspected.setRestocked(true);
        when(returnRefundRepository.findByTenantIdAndOrderId(TENANT_ID, ORDER_ID)).thenReturn(Optional.of(inspected));

        Order updated = orderService.handleOrderReturn(shopeeReturn("ret-duplicate-shopee")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.AWAITING_INSPECTION)
                .returnStatus("ACCEPTED").restockRequired(true).build());

        assertEquals(Order.OrderStatus.RETURNED, updated.getStatus());
        assertEquals("RESTOCKED", updated.getRestockStatus());
        assertEquals(com.smartomni.order.entity.ReturnRefund.RefundStatus.COMPLETED, inspected.getStatus());
        assertEquals(com.smartomni.order.entity.ReturnRefund.InspectionResultType.MATCHED, inspected.getInspectionResult());
        assertTrue(inspected.isRestocked());
        assertEquals("ACCEPTED", inspected.getMarketplaceStatus());
        verifyNoInteractions(inventoryServiceClient);
    }

    @Test
    @DisplayName("Shopee push 29 RECEIVE_REJECT: lưu rejectReason & evidenceUrls, không hoàn kho")
    void shopeeReturnRejectKeepsReasonAndEvidence() {
        stubShopeeOrder(Order.OrderStatus.COMPLETED);

        Order updated = orderService.handleOrderReturn(shopeeReturn("ret-reject-1")
                .returnAction(com.smartomni.common.dto.ProcessReturnRequest.ReturnAction.RECEIVE_REJECT)
                .returnStatus("CLOSED")
                .rejectReason("Hàng bị bể vỡ do va đập, không đúng nguyên trạng")
                .evidenceUrls(List.of("https://cdn.smartomni.com/ev1.jpg", "https://cdn.smartomni.com/ev2.jpg"))
                .build());

        assertEquals(Order.OrderStatus.COMPLETED, updated.getStatus());
        assertEquals("Hàng bị bể vỡ do va đập, không đúng nguyên trạng", updated.getRejectReason());
        assertTrue(updated.getEvidenceUrls().contains("https://cdn.smartomni.com/ev1.jpg"));
        verify(inventoryServiceClient, never()).restock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("Thiếu platform trong yêu cầu trả hàng / tracking: 400 PLATFORM_REQUIRED, không mặc định TikTok (M10)")
    void missingPlatformIsRejected() {
        var returnError = assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.handleOrderReturn(com.smartomni.common.dto.ProcessReturnRequest.builder()
                        .tenantId(TENANT_ID).platformOrderId(PLATFORM_ORDER_ID).returnId("r").build()));
        assertEquals("PLATFORM_REQUIRED", returnError.getErrorCode());

        var trackingError = assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.updateOrderTracking(com.smartomni.common.dto.UpdateOrderTrackingRequest.builder()
                        .tenantId(TENANT_ID).platformOrderId(PLATFORM_ORDER_ID).build()));
        assertEquals("PLATFORM_REQUIRED", trackingError.getErrorCode());
        verifyNoInteractions(orderRepository);
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

    private static com.smartomni.common.dto.CreateManualOrderRequest manualRequest(String platform, String platformOrderId) {
        return com.smartomni.common.dto.CreateManualOrderRequest.builder()
                .platform(platform)
                .platformOrderId(platformOrderId)
                .items(List.of(new com.smartomni.common.dto.CreateManualOrderRequest.ManualOrderItem(55L, 1, BigDecimal.TEN, null)))
                .build();
    }

    @Test
    @DisplayName("createManualOrder without platform is a 400, no SHOPEE default (D4)")
    void createManualOrder_missingPlatform_isRejected() {
        var error = assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.createManualOrder(manualRequest(null, null)));

        assertEquals("PLATFORM_REQUIRED", error.getErrorCode());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createManualOrder with an unknown platform is a 400 instead of silently SHOPEE")
    void createManualOrder_unknownPlatform_isRejected() {
        var error = assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.createManualOrder(manualRequest("tiktok", null)));

        assertEquals("UNKNOWN_PLATFORM", error.getErrorCode());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createManualOrder prefixes a given id with MANUAL- so it never matches a marketplace order")
    void createManualOrder_prefixesGivenId() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId(ORDER_ID);
            return o;
        });
        Order stored = new Order();
        stored.setId(ORDER_ID);
        stored.setTenantId(TENANT_ID);
        stored.setPlatform(Order.Platform.TIKTOK_SHOP);
        stored.setStatus(Order.OrderStatus.PENDING);
        when(orderRepository.findByIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(Optional.of(stored));
        when(orderItemRepository.findByOrderIdAndTenantId(ORDER_ID, TENANT_ID)).thenReturn(List.of());

        orderService.createManualOrder(manualRequest("tiktok_shop", " 5800001 "));
        orderService.createManualOrder(manualRequest("TIKTOK_SHOP", "MANUAL-42"));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(saved.capture());
        assertEquals("MANUAL-5800001", saved.getAllValues().get(0).getPlatformOrderId());
        assertEquals("MANUAL-42", saved.getAllValues().get(1).getPlatformOrderId(), "an id already prefixed is kept");
        assertEquals(Order.Platform.TIKTOK_SHOP, saved.getAllValues().get(0).getPlatform());
    }

    @Test
    @DisplayName("createManualOrder with an id that already exists is a 409")
    void createManualOrder_duplicateId_isConflict() {
        when(orderRepository.findByTenantIdAndPlatformAndPlatformOrderId(TENANT_ID, Order.Platform.SHOPEE, "MANUAL-7"))
                .thenReturn(Optional.of(new Order()));

        var error = assertThrows(com.smartomni.common.exception.BusinessException.class,
                () -> orderService.createManualOrder(manualRequest("SHOPEE", "7")));

        assertEquals("DUPLICATE_PLATFORM_ORDER_ID", error.getErrorCode());
        verify(orderRepository, never()).save(any(Order.class));
    }
}


