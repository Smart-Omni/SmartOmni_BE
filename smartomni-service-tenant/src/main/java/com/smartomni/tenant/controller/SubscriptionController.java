package com.smartomni.tenant.controller;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.tenant.dto.SubscriptionCheckoutResponse;
import com.smartomni.tenant.dto.SubscriptionInvoiceDto;
import com.smartomni.tenant.dto.SubscriptionOrderStatusResponse;
import com.smartomni.tenant.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/tenants/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @PostMapping("/checkout")
    public ResponseEntity<SubscriptionCheckoutResponse> checkout() {
        Long tenantId = TenantContext.getTenantId();
        Long userId = TenantContext.getCurrentUserId();
        SubscriptionCheckoutResponse response = subscriptionService.createCheckoutSession(tenantId, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/orders/{orderCode}/status")
    public ResponseEntity<SubscriptionOrderStatusResponse> getOrderStatus(@PathVariable String orderCode) {
        Long tenantId = TenantContext.getTenantId();
        SubscriptionOrderStatusResponse response = subscriptionService.getOrderStatus(tenantId, orderCode);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/invoices")
    public ResponseEntity<List<SubscriptionInvoiceDto>> getInvoices() {
        Long tenantId = TenantContext.getTenantId();
        List<SubscriptionInvoiceDto> response = subscriptionService.getTenantInvoices(tenantId);
        return ResponseEntity.ok(response);
    }
}
