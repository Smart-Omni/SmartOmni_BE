package com.smartomni.tenant.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.tenant.dto.SubscriptionCheckoutResponse;
import com.smartomni.tenant.dto.SubscriptionInvoiceDto;
import com.smartomni.tenant.dto.SubscriptionOrderStatusResponse;
import com.smartomni.tenant.dto.SubscriptionSummaryResponse;
import com.smartomni.tenant.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/tenants/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/status")
    public ApiResponse<SubscriptionSummaryResponse> getSubscriptionSummary() {
        Long tenantId = TenantContext.getTenantId();
        SubscriptionSummaryResponse response = subscriptionService.getSubscriptionSummary(tenantId);
        return ApiResponse.success(response);
    }

    @PostMapping("/checkout")
    public ApiResponse<SubscriptionCheckoutResponse> checkout() {
        Long tenantId = TenantContext.getTenantId();
        Long userId = TenantContext.getCurrentUserId();
        SubscriptionCheckoutResponse response = subscriptionService.createCheckoutSession(tenantId, userId);
        return ApiResponse.success("Tạo phiên thanh toán thành công", response);
    }

    @GetMapping("/orders/{orderCode}/status")
    public ApiResponse<SubscriptionOrderStatusResponse> getOrderStatus(@PathVariable String orderCode) {
        Long tenantId = TenantContext.getTenantId();
        SubscriptionOrderStatusResponse response = subscriptionService.getOrderStatus(tenantId, orderCode);
        return ApiResponse.success(response);
    }

    @PostMapping("/orders/{orderCode}/simulate-pay")
    public ApiResponse<Boolean> simulatePayment(@PathVariable String orderCode) {
        Long tenantId = TenantContext.getTenantId();
        boolean success = subscriptionService.simulatePaymentSuccess(tenantId, orderCode);
        return ApiResponse.success("Giả lập thanh toán SePay thành công", success);
    }

    @GetMapping("/invoices")
    public ApiResponse<List<SubscriptionInvoiceDto>> getInvoices() {
        Long tenantId = TenantContext.getTenantId();
        List<SubscriptionInvoiceDto> response = subscriptionService.getTenantInvoices(tenantId);
        return ApiResponse.success(response);
    }
}
