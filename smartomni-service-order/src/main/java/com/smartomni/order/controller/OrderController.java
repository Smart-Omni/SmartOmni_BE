package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.order.entity.Order;
import com.smartomni.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

/**
 * UC-10: Theo doi & xu ly don hang (Manager)
 * UC-06: Tra cuu don hang cheo nen tang (Khach hang - endpoint /track khong yeu cau dang nhap)
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ApiResponse<Page<Order>> list(Pageable pageable) {
        return ApiResponse.success(orderService.getOrdersByTenant(TenantContext.getTenantId(), pageable));
    }

    @PatchMapping("/{orderId}/status")
    public ApiResponse<Order> updateStatus(@PathVariable Long orderId, @RequestParam Order.OrderStatus status) {
        return ApiResponse.success(orderService.updateStatus(orderId, status));
    }

    /** Cập nhật thông tin vận đơn và tracking (Giai đoạn 4 - Bước 4.1). */
    @PatchMapping("/tracking")
    public ApiResponse<Order> updateTracking(@RequestBody com.smartomni.common.dto.UpdateOrderTrackingRequest request) {
        return ApiResponse.success(orderService.updateOrderTracking(request));
    }

    /** Xử lý yêu cầu hoàn hàng / trả hàng (Giai đoạn 4 - Bước 4.2). */
    @PostMapping("/returns")
    public ApiResponse<Order> handleReturn(@RequestBody com.smartomni.common.dto.ProcessReturnRequest request) {
        return ApiResponse.success(orderService.handleOrderReturn(request));
    }

    /** UC-06: Public endpoint - khach hang tra cuu bang Order ID, khong can JWT. */
    @GetMapping("/track")
    public ApiResponse<Order> track(@RequestParam String platform, @RequestParam String orderId) {
        return ApiResponse.success(orderService.trackOrder(platform, orderId));
    }
}
