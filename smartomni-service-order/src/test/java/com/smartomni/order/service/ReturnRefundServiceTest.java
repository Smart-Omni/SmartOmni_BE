package com.smartomni.order.service;

import com.smartomni.common.dto.ReturnDetailDto;
import com.smartomni.common.dto.ReturnInspectionRequest;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.client.InventoryServiceClient;
import com.smartomni.order.dto.CreateReturnRequest;
import com.smartomni.order.entity.Order;
import com.smartomni.order.entity.OrderItem;
import com.smartomni.order.entity.ReturnRefund;
import com.smartomni.order.entity.ReturnRefundItem;
import com.smartomni.order.repository.OrderItemRepository;
import com.smartomni.order.repository.OrderRepository;
import com.smartomni.order.repository.OrderStatusHistoryRepository;
import com.smartomni.order.repository.ReturnRefundItemRepository;
import com.smartomni.order.repository.ReturnRefundRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReturnRefundService Unit Tests")
class ReturnRefundServiceTest {

    @Mock ReturnRefundRepository refunds;
    @Mock ReturnRefundItemRepository refundItems;
    @Mock OrderRepository orders;
    @Mock OrderItemRepository items;
    @Mock OrderStatusHistoryRepository statusHistories;
    @Mock InventoryServiceClient inventoryServiceClient;

    @InjectMocks ReturnRefundService service;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void cannotCreateRefundForAnotherTenantOrder() {
        TenantContext.setTenantId(2L);
        CreateReturnRequest request = new CreateReturnRequest();
        request.setOrderId(7L);
        when(orders.findByIdAndTenantId(7L, 2L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.createReturnRequest(request));
        verifyNoInteractions(refunds, items);
    }

    @Test
    void cannotApproveAnotherTenantRefund() {
        TenantContext.setTenantId(2L);
        when(refunds.findByIdAndTenantId(8L, 2L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.approveAndRestock(8L));
        verifyNoInteractions(orders, items);
    }

    @Test
    @DisplayName("TikTok (07b.3/07b.4): inspection is recorded only; no order status change and no restock")
    void performInspection_tiktokOrderRecordsResultOnly() {
        TenantContext.setTenantId(1L);
        ReturnRefund refund = new ReturnRefund();
        refund.setId(8L);
        refund.setTenantId(1L);
        refund.setOrderId(10L);
        refund.setStatus(ReturnRefund.RefundStatus.APPROVED);
        refund.setMarketplaceStatus("BUYER_SHIPPED_ITEM");
        Order order = new Order();
        order.setId(10L);
        order.setTenantId(1L);
        order.setPlatform(Order.Platform.TIKTOK_SHOP);
        order.setStatus(Order.OrderStatus.COMPLETED);
        when(refunds.findByIdAndTenantId(8L, 1L)).thenReturn(Optional.of(refund));
        when(orders.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(order));

        ReturnDetailDto result = service.performInspection(8L, ReturnInspectionRequest.builder()
                .result(ReturnInspectionRequest.InspectionResult.MATCHED).build());

        assertEquals("MATCHED", result.getInspectionResult());
        assertEquals("APPROVED", result.getStatus());
        assertEquals("BUYER_SHIPPED_ITEM", result.getMarketplaceStatus());
        assertEquals(Order.OrderStatus.COMPLETED, order.getStatus());
        verify(orders, never()).save(any());
        verifyNoInteractions(inventoryServiceClient, statusHistories);
    }

    @Test
    @DisplayName("Inspection MATCHED: updates return to COMPLETED, restocks with idempotency key, sets order RETURNED")
    void performInspection_matched_restocksInventoryAndMarksCompleted() {
        TenantContext.setTenantId(1L);

        ReturnRefund refund = new ReturnRefund();
        refund.setId(8L);
        refund.setTenantId(1L);
        refund.setOrderId(10L);
        refund.setConnectionId(88L);
        refund.setExternalReturnId("RET-SP-100");
        refund.setStatus(ReturnRefund.RefundStatus.REQUESTED);
        refund.setInspectionResult(ReturnRefund.InspectionResultType.PENDING);

        Order order = new Order();
        order.setId(10L);
        order.setTenantId(1L);
        order.setStatus(Order.OrderStatus.ON_HOLD);

        ReturnRefundItem item = new ReturnRefundItem();
        item.setId(1L);
        item.setTenantId(1L);
        item.setReturnRefundId(8L);
        item.setSkuId(55L);
        item.setQuantityReturned(2);

        when(refunds.findByIdAndTenantId(8L, 1L)).thenReturn(Optional.of(refund));
        when(orders.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(order));
        when(refundItems.findByTenantIdAndReturnRefundId(1L, 8L)).thenReturn(List.of(item));

        ReturnInspectionRequest request = ReturnInspectionRequest.builder()
                .result(ReturnInspectionRequest.InspectionResult.MATCHED)
                .videoEvidenceUrl("https://storage/evidence/vid1.mp4")
                .note("Goods in pristine condition")
                .build();

        ReturnDetailDto result = service.performInspection(8L, request);

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertEquals("MATCHED", result.getInspectionResult());
        assertTrue(result.isRestocked());
        assertEquals(Order.OrderStatus.RETURNED, order.getStatus());
        assertEquals("RESTOCKED", order.getRestockStatus());

        String expectedIdempotencyKey = "1:88:RET-SP-100:55:RESTOCK";
        verify(inventoryServiceClient).restock(55L, 2, expectedIdempotencyKey);
        verify(refunds).save(refund);
        verify(orders).save(order);
        verify(statusHistories).save(any());
    }

    @Test
    @DisplayName("Inspection DAMAGED: marks REJECTED, sets order ON_HOLD, strictly does NOT restock")
    void performInspection_damaged_doesNotRestock() {
        TenantContext.setTenantId(1L);

        ReturnRefund refund = new ReturnRefund();
        refund.setId(8L);
        refund.setTenantId(1L);
        refund.setOrderId(10L);
        refund.setStatus(ReturnRefund.RefundStatus.REQUESTED);

        Order order = new Order();
        order.setId(10L);
        order.setTenantId(1L);
        order.setStatus(Order.OrderStatus.ON_HOLD);

        when(refunds.findByIdAndTenantId(8L, 1L)).thenReturn(Optional.of(refund));
        when(orders.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(order));

        ReturnInspectionRequest request = ReturnInspectionRequest.builder()
                .result(ReturnInspectionRequest.InspectionResult.DAMAGED)
                .videoEvidenceUrl("https://storage/evidence/damaged.mp4")
                .note("Box crushed and product broken")
                .build();

        ReturnDetailDto result = service.performInspection(8L, request);

        assertNotNull(result);
        assertEquals("REJECTED", result.getStatus());
        assertEquals("DAMAGED", result.getInspectionResult());
        assertFalse(result.isRestocked());
        assertEquals(Order.OrderStatus.ON_HOLD, order.getStatus());
        assertEquals("INSPECTION_REJECTED", order.getRestockStatus());

        verifyNoInteractions(inventoryServiceClient);
        verify(refunds).save(refund);
        verify(orders).save(order);
        verify(statusHistories).save(any());
    }

    @Test
    @DisplayName("getReturns: returns paginated DTO list")
    void getReturns_returnsPagedList() {
        TenantContext.setTenantId(1L);
        ReturnRefund refund = new ReturnRefund();
        refund.setId(8L);
        refund.setTenantId(1L);
        refund.setOrderId(10L);

        when(refunds.findByTenantId(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(refund)));
        when(orders.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(new Order()));

        Page<ReturnDetailDto> page = service.getReturns(PageRequest.of(0, 10));

        assertNotNull(page);
        assertEquals(1, page.getTotalElements());
        assertEquals(8L, page.getContent().get(0).getId());
    }

    @Test
    @DisplayName("legacy approveAndRestock: executes inspection MATCHED and safely restocks")
    void approveAndRestock_delegatesToInspectionAndRestocks() {
        TenantContext.setTenantId(1L);

        ReturnRefund refund = new ReturnRefund();
        refund.setId(8L);
        refund.setTenantId(1L);
        refund.setOrderId(10L);
        refund.setConnectionId(99L);
        refund.setExternalReturnId("RET-LEGACY");

        Order order = new Order();
        order.setId(10L);
        order.setTenantId(1L);

        OrderItem orderItem = new OrderItem();
        orderItem.setSkuId(77L);
        orderItem.setQuantity(3);

        when(refunds.findByIdAndTenantId(8L, 1L)).thenReturn(Optional.of(refund));
        when(orders.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(order));
        when(refundItems.findByTenantIdAndReturnRefundId(1L, 8L)).thenReturn(List.of());
        when(items.findByOrderIdAndTenantId(10L, 1L)).thenReturn(List.of(orderItem));

        ReturnRefund approved = service.approveAndRestock(8L);

        assertNotNull(approved);
        assertEquals(ReturnRefund.RefundStatus.COMPLETED, approved.getStatus());
        assertTrue(approved.isRestocked());
        verify(inventoryServiceClient).restock(77L, 3, "1:99:RET-LEGACY:77:RESTOCK");
    }
}
