package com.smartomni.tenant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.tenant.config.SepayProperties;
import com.smartomni.tenant.dto.SubscriptionCheckoutResponse;
import com.smartomni.tenant.dto.SubscriptionInvoiceDto;
import com.smartomni.tenant.dto.SubscriptionOrderStatusResponse;
import com.smartomni.tenant.entity.*;
import com.smartomni.tenant.repository.*;
import com.smartomni.tenant.util.SepaySignatureValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionServiceImpl Unit Tests")
class SubscriptionServiceTest {

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantInvoiceRepository invoiceRepository;

    @Mock
    private SubscriptionPaymentRepository paymentRepository;

    @Mock
    private TenantSubscriptionHistoryRepository historyRepository;

    @Spy
    private SepayProperties sepayProperties = new SepayProperties();

    @Mock
    private SepaySignatureValidator signatureValidator;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SubscriptionServiceImpl subscriptionService;

    private Tenant tenant;
    private SubscriptionPlan proPlan;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(10L);
        tenant.setName("Test Tenant");
        tenant.setPlanType("FREE");
        tenant.setPlanId(1L);

        proPlan = new SubscriptionPlan();
        proPlan.setId(2L);
        proPlan.setName("PRO");
        proPlan.setPrice(new BigDecimal("499000.00"));
    }

    @Test
    @DisplayName("createCheckoutSession: Tạo phiên thanh toán nâng cấp PRO thành công -> Trả về QR và orderCode")
    void createCheckoutSession_success() {
        when(tenantRepository.findById(10L)).thenReturn(Optional.of(tenant));
        when(subscriptionPlanRepository.findByName("PRO")).thenReturn(Optional.of(proPlan));

        TenantInvoice mockInvoice = new TenantInvoice();
        mockInvoice.setId(101L);
        mockInvoice.setTenantId(10L);
        mockInvoice.setAmount(proPlan.getPrice());
        when(invoiceRepository.save(any(TenantInvoice.class))).thenReturn(mockInvoice);

        SubscriptionPayment mockPayment = new SubscriptionPayment();
        mockPayment.setId(201L);
        mockPayment.setInvoiceId(101L);
        mockPayment.setTenantId(10L);
        mockPayment.setAmount(proPlan.getPrice());
        when(paymentRepository.save(any(SubscriptionPayment.class))).thenReturn(mockPayment);

        SubscriptionCheckoutResponse response = subscriptionService.createCheckoutSession(10L, 1L);

        assertNotNull(response);
        assertEquals(new BigDecimal("499000.00"), response.getAmount());
        assertEquals("PRO", response.getPlanName());
        assertTrue(response.getOrderCode().contains("PRO"));
        assertTrue(response.getQrUrl().contains("vietqr.app/img"));
        assertEquals(sepayProperties.getAccountNumber(), response.getAccountNumber());
        assertNotNull(response.getExpiresAt());
    }

    @Test
    @DisplayName("createCheckoutSession: Edge case - Tenant null -> Ném BusinessException")
    void createCheckoutSession_nullTenant_throwsException() {
        assertThrows(BusinessException.class, () -> subscriptionService.createCheckoutSession(null, 1L));
    }

    @Test
    @DisplayName("createCheckoutSession: Edge case - Gói PRO chưa cấu hình -> Ném BusinessException")
    void createCheckoutSession_proPlanNotFound_throwsException() {
        when(tenantRepository.findById(10L)).thenReturn(Optional.of(tenant));
        when(subscriptionPlanRepository.findByName("PRO")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> subscriptionService.createCheckoutSession(10L, 1L));
    }

    @Test
    @DisplayName("getOrderStatus: Đơn hàng đã thanh toán -> Trả về PAID và planName=PRO")
    void getOrderStatus_paid() {
        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setId(201L);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(Instant.now());

        when(paymentRepository.findByIdAndTenantId(201L, 10L)).thenReturn(Optional.of(payment));

        SubscriptionOrderStatusResponse response = subscriptionService.getOrderStatus(10L, "PRO001000201");

        assertEquals("PAID", response.getStatus());
        assertEquals("PRO", response.getPlanName());
        assertNotNull(response.getPaidAt());
    }

    @Test
    @DisplayName("getOrderStatus: Đơn hàng đang chờ -> Trả về PENDING")
    void getOrderStatus_pending() {
        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setId(201L);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(Instant.now());

        when(paymentRepository.findByIdAndTenantId(201L, 10L)).thenReturn(Optional.of(payment));

        SubscriptionOrderStatusResponse response = subscriptionService.getOrderStatus(10L, "PRO001000201");

        assertEquals("PENDING", response.getStatus());
        assertEquals("FREE", response.getPlanName());
    }

    @Test
    @DisplayName("getOrderStatus: Edge case - Đơn hàng quá 24h chưa thanh toán -> Trả về EXPIRED")
    void getOrderStatus_expired() {
        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setId(201L);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(Instant.now().minus(25, ChronoUnit.HOURS));

        when(paymentRepository.findByIdAndTenantId(201L, 10L)).thenReturn(Optional.of(payment));

        SubscriptionOrderStatusResponse response = subscriptionService.getOrderStatus(10L, "PRO001000201");

        assertEquals("EXPIRED", response.getStatus());
    }

    @Test
    @DisplayName("getTenantInvoices: Lấy danh sách hóa đơn theo tenant -> Thành công")
    void getTenantInvoices_success() {
        TenantInvoice invoice = new TenantInvoice();
        invoice.setId(101L);
        invoice.setAmount(new BigDecimal("499000.00"));
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setBillingPeriodStart(LocalDate.now());
        invoice.setBillingPeriodEnd(LocalDate.now().plusMonths(1));

        when(invoiceRepository.findByTenantIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(invoice));

        List<SubscriptionInvoiceDto> result = subscriptionService.getTenantInvoices(10L);

        assertEquals(1, result.size());
        assertEquals(101L, result.get(0).getId());
        assertEquals("PAID", result.get(0).getStatus());
    }

    @Test
    @DisplayName("processWebhook: Thanh toán hợp lệ -> Cập nhật Invoice PAID, Payment SUCCESS, nâng Tenant lên PRO")
    void processWebhook_success() {
        String payloadJson = "{"
                + "\"id\": 999123,"
                + "\"gateway\": \"MBBank\","
                + "\"transferType\": \"in\","
                + "\"transferAmount\": 499000,"
                + "\"code\": \"PRO001000201\","
                + "\"content\": \"PRO001000201 chuyen khoan nang cap\""
                + "}";

        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(true);
        when(paymentRepository.findByGatewayTransactionId("999123")).thenReturn(Optional.empty());

        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setId(201L);
        payment.setInvoiceId(101L);
        payment.setTenantId(10L);
        payment.setAmount(new BigDecimal("499000.00"));
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findById(201L)).thenReturn(Optional.of(payment));

        TenantInvoice invoice = new TenantInvoice();
        invoice.setId(101L);
        invoice.setStatus(InvoiceStatus.PENDING);
        when(invoiceRepository.findById(101L)).thenReturn(Optional.of(invoice));

        when(subscriptionPlanRepository.findByName("PRO")).thenReturn(Optional.of(proPlan));
        when(tenantRepository.findById(10L)).thenReturn(Optional.of(tenant));

        boolean result = subscriptionService.processWebhook(payloadJson, "valid_sig", "1700000000");

        assertTrue(result);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("999123", payment.getGatewayTransactionId());
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        assertEquals("PRO", tenant.getPlanType());
        assertEquals(proPlan.getId(), tenant.getPlanId());

        verify(paymentRepository).save(payment);
        verify(invoiceRepository).save(invoice);
        verify(tenantRepository).save(tenant);
        verify(historyRepository).save(any(TenantSubscriptionHistory.class));
    }

    @Test
    @DisplayName("processWebhook: Edge case - Chữ ký không hợp lệ -> Trả về false không chạm database")
    void processWebhook_invalidSignature_returnsFalse() {
        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(false);

        boolean result = subscriptionService.processWebhook("{}", "bad_sig", "1700000000");

        assertFalse(result);
        verifyNoInteractions(paymentRepository, tenantRepository, invoiceRepository);
    }

    @Test
    @DisplayName("processWebhook: Edge case - Giao dịch trùng lặp (Idempotency) -> Trả về kết quả cũ, không cập nhật lại")
    void processWebhook_idempotentDuplicate_returnsPreviousResult() {
        String payloadJson = "{\"id\": 999123, \"code\": \"PRO001000201\"}";

        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(true);

        SubscriptionPayment alreadyPaid = new SubscriptionPayment();
        alreadyPaid.setStatus(PaymentStatus.SUCCESS);
        when(paymentRepository.findByGatewayTransactionId("999123")).thenReturn(Optional.of(alreadyPaid));

        boolean result = subscriptionService.processWebhook(payloadJson, "sig", "1700000000");

        assertTrue(result);
        verify(paymentRepository, never()).save(any());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("processWebhook: Edge case - Chuyển khoản thiếu tiền (Underpayment) -> Đánh dấu anomaly, không nâng gói")
    void processWebhook_underpayment_flagsAnomalyAndFails() {
        String payloadJson = "{"
                + "\"id\": 999123,"
                + "\"transferType\": \"in\","
                + "\"transferAmount\": 100000," // Thiếu so với 499,000 đ
                + "\"code\": \"PRO001000201\""
                + "}";

        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(true);
        when(paymentRepository.findByGatewayTransactionId("999123")).thenReturn(Optional.empty());

        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setId(201L);
        payment.setAmount(new BigDecimal("499000.00"));
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findById(201L)).thenReturn(Optional.of(payment));

        boolean result = subscriptionService.processWebhook(payloadJson, "sig", "1700000000");

        assertFalse(result);
        assertTrue(payment.getIsAnomaly());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        verify(paymentRepository).save(payment);
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("processWebhook: Edge case - Giao dịch tiền ra (transferType != 'in') -> Bỏ qua và trả về false")
    void processWebhook_transferTypeOut_fails() {
        String payloadJson = "{\"id\": 999123, \"transferType\": \"out\", \"transferAmount\": 499000}";

        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(true);
        when(paymentRepository.findByGatewayTransactionId("999123")).thenReturn(Optional.empty());

        boolean result = subscriptionService.processWebhook(payloadJson, "sig", "1700000000");

        assertFalse(result);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("processWebhook: Edge case - Mã thanh toán không khớp đơn nào trong hệ thống -> Trả về false")
    void processWebhook_unmatchedCode_fails() {
        String payloadJson = "{\"id\": 999123, \"transferType\": \"in\", \"transferAmount\": 499000, \"code\": \"PRO999999999\"}";

        when(signatureValidator.validate(any(), any(), any(), any())).thenReturn(true);
        when(paymentRepository.findByGatewayTransactionId("999123")).thenReturn(Optional.empty());
        when(paymentRepository.findById(99999L)).thenReturn(Optional.empty());

        boolean result = subscriptionService.processWebhook(payloadJson, "sig", "1700000000");

        assertFalse(result);
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("simulatePaymentSuccess: Giả lập thanh toán thành công -> Cập nhật payment, invoice và upgrade tenant lên PRO")
    void simulatePaymentSuccess_validOrderCode_succeeds() {
        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setId(201L);
        payment.setTenantId(10L);
        payment.setInvoiceId(101L);
        payment.setAmount(new BigDecimal("499000.00"));
        payment.setStatus(PaymentStatus.PENDING);

        TenantInvoice invoice = new TenantInvoice();
        invoice.setId(101L);
        invoice.setStatus(InvoiceStatus.PENDING);

        when(paymentRepository.findByIdAndTenantId(201L, 10L)).thenReturn(Optional.of(payment));
        when(invoiceRepository.findById(101L)).thenReturn(Optional.of(invoice));
        when(subscriptionPlanRepository.findByName("PRO")).thenReturn(Optional.of(proPlan));
        when(tenantRepository.findById(10L)).thenReturn(Optional.of(tenant));

        boolean result = subscriptionService.simulatePaymentSuccess(10L, "PRO001000201");

        assertTrue(result);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        assertEquals(2L, tenant.getPlanId());
        assertEquals("PRO", tenant.getPlanType());
        verify(paymentRepository).save(payment);
        verify(invoiceRepository).save(invoice);
        verify(tenantRepository).save(tenant);
    }
}
