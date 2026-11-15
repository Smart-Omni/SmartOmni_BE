package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.CreateManualOrderRequest;
import com.smartomni.common.dto.OrderDetailDto;
import com.smartomni.common.dto.OrderItemDto;
import com.smartomni.order.entity.Order;
import com.smartomni.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("OrderController Unit Tests")
class OrderControllerTest {

    private OrderService orderService;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        controller = new OrderController(orderService);
    }

    @Test
    void getDetailReturnsOrderDetailWithItems() {
        OrderDetailDto detailDto = OrderDetailDto.builder()
                .id(100L)
                .platform("SHOPEE")
                .platformOrderId("SP-ORDER-123")
                .status("PENDING")
                .items(List.of(
                        OrderItemDto.builder().id(1L).skuId(10L).quantity(2).unitPrice(BigDecimal.valueOf(50000)).build()
                ))
                .build();

        when(orderService.getOrderDetail(100L)).thenReturn(detailDto);

        ApiResponse<OrderDetailDto> response = controller.getDetail(100L);

        assertNotNull(response);
        assertEquals(100L, response.getData().getId());
        assertEquals(1, response.getData().getItems().size());
        assertEquals(10L, response.getData().getItems().get(0).getSkuId());
        verify(orderService).getOrderDetail(100L);
    }

    @Test
    void createManualOrderReturnsCreatedDetail() {
        CreateManualOrderRequest request = CreateManualOrderRequest.builder()
                .platform("SHOPEE")
                .customerName("Nguyen Van A")
                .items(List.of(new CreateManualOrderRequest.ManualOrderItem(10L, 2, BigDecimal.valueOf(50000), null)))
                .build();

        OrderDetailDto created = OrderDetailDto.builder()
                .id(101L)
                .platform("SHOPEE")
                .source("MANUAL")
                .customerName("Nguyen Van A")
                .build();

        when(orderService.createManualOrder(any())).thenReturn(created);

        ApiResponse<OrderDetailDto> response = controller.createManualOrder(request);

        assertNotNull(response);
        assertEquals("Tạo đơn hàng thành công", response.getMessage());
        assertEquals(101L, response.getData().getId());
        verify(orderService).createManualOrder(request);
    }

    @Test
    void listReturnsPaginatedOrders() {
        Order order = new Order();
        order.setId(100L);
        when(orderService.getOrdersByTenant(any(), any())).thenReturn(new PageImpl<>(List.of(order)));

        ApiResponse<Page<Order>> response = controller.list(PageRequest.of(0, 10));

        assertNotNull(response);
        assertEquals(1, response.getData().getTotalElements());
    }
}
