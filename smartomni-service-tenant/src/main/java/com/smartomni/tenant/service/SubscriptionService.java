package com.smartomni.tenant.service;

import com.smartomni.tenant.dto.SubscriptionCheckoutResponse;
import com.smartomni.tenant.dto.SubscriptionInvoiceDto;
import com.smartomni.tenant.dto.SubscriptionOrderStatusResponse;

import java.util.List;

public interface SubscriptionService {

    SubscriptionCheckoutResponse createCheckoutSession(Long tenantId, Long userId);

    SubscriptionOrderStatusResponse getOrderStatus(Long tenantId, String orderCode);

    List<SubscriptionInvoiceDto> getTenantInvoices(Long tenantId);

    boolean processWebhook(String rawBody, String signature, String timestamp);

    boolean simulatePaymentSuccess(Long tenantId, String orderCode);
}
