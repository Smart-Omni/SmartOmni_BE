package com.smartomni.tenant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.persistence.RlsSession;
import com.smartomni.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import com.smartomni.tenant.config.SepayProperties;
import com.smartomni.tenant.dto.*;
import com.smartomni.tenant.entity.*;
import com.smartomni.tenant.repository.*;
import com.smartomni.tenant.service.SubscriptionService;
import com.smartomni.tenant.util.SepaySignatureValidator;
import com.smartomni.tenant.util.SepayVietQrHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private static final Pattern ORDER_CODE_PATTERN = Pattern.compile("PRO(\\d{4})(\\d{5})");
    private static final Pattern FALLBACK_PRO_PATTERN = Pattern.compile("PRO\\d+");

    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final TenantRepository tenantRepository;
    private final TenantInvoiceRepository invoiceRepository;
    private final SubscriptionPaymentRepository paymentRepository;
    private final TenantSubscriptionHistoryRepository historyRepository;
    private final SepayProperties sepayProperties;
    private final SepaySignatureValidator signatureValidator;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public SubscriptionCheckoutResponse createCheckoutSession(Long tenantId, Long userId) {
        if (tenantId == null) {
            throw new BusinessException("Tenant ID is required to create a subscription checkout", HttpStatus.BAD_REQUEST, "TENANT_REQUIRED");
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException("Tenant not found", HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND"));

        // Early renewal check:
        // If tenant is currently PRO and period has NOT ended:
        if (isProTenant(tenant)) {
            Optional<TenantInvoice> activeInvoiceOpt = invoiceRepository
                    .findFirstByTenantIdAndStatusOrderByBillingPeriodEndDesc(tenant.getId(), InvoiceStatus.PAID);
            if (activeInvoiceOpt.isPresent() && activeInvoiceOpt.get().getBillingPeriodEnd() != null) {
                LocalDate periodEnd = activeInvoiceOpt.get().getBillingPeriodEnd();
                long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), periodEnd);
                if (daysRemaining > 5) {
                    throw new BusinessException(
                            "Gói Pro của bạn vẫn còn " + daysRemaining + " ngày hiệu lực. Bạn chỉ có thể gia hạn sớm khi còn từ 5 ngày trở xuống.",
                            HttpStatus.BAD_REQUEST,
                            "SUBSCRIPTION_EARLY_RENEWAL_TOO_EARLY"
                    );
                }
            }
        }

        SubscriptionPlan proPlan = subscriptionPlanRepository.findByName("PRO")
                .or(() -> subscriptionPlanRepository.findByName("Pro"))
                .orElseThrow(() -> new BusinessException("Gói dịch vụ PRO chưa được cấu hình", HttpStatus.NOT_FOUND, "SUBSCRIPTION_PLAN_NOT_FOUND"));

        BigDecimal amount = proPlan.getPrice() != null && proPlan.getPrice().compareTo(BigDecimal.ZERO) > 0
                ? proPlan.getPrice()
                : sepayProperties.getProPlanPrice();

        // Option 2 (Stripe/Shopify model): Checkout sessions do not create pending invoices.
        // Invoices are only created upon confirmed payment. Reuse pending payment session if within 15 minutes.
        SubscriptionPayment payment = paymentRepository
                .findFirstByTenantIdAndStatusOrderByCreatedAtDesc(tenant.getId(), PaymentStatus.PENDING)
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(Instant.now().minus(15, ChronoUnit.MINUTES)))
                .orElseGet(() -> {
                    SubscriptionPayment newPayment = new SubscriptionPayment();
                    newPayment.setTenantId(tenant.getId());
                    newPayment.setAmount(amount);
                    newPayment.setPaymentMethod("SEPAY");
                    newPayment.setStatus(PaymentStatus.PENDING);
                    newPayment.setConfirmedBy(userId);
                    return paymentRepository.save(newPayment);
                });

        String orderCode = SepayVietQrHelper.generateOrderCode(tenant.getId(), payment.getId());
        String qrUrl = SepayVietQrHelper.buildQrUrl(
                sepayProperties.getAccountNumber(),
                sepayProperties.getBankId(),
                amount,
                orderCode,
                sepayProperties.getQrTemplate()
        );

        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);

        log.info("Created subscription checkout session: orderCode={}, tenantId={}, amount={}", orderCode, tenantId, amount);

        return SubscriptionCheckoutResponse.builder()
                .orderCode(orderCode)
                .amount(amount)
                .qrUrl(qrUrl)
                .accountNumber(sepayProperties.getAccountNumber())
                .bankId(sepayProperties.getBankId())
                .accountHolder(sepayProperties.getAccountHolder())
                .memo(orderCode)
                .planName("PRO")
                .expiresAt(expiresAt)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionOrderStatusResponse getOrderStatus(Long tenantId, String orderCode) {
        if (orderCode == null || orderCode.isBlank()) {
            return SubscriptionOrderStatusResponse.builder()
                    .orderCode(orderCode)
                    .status("FAILED")
                    .planName("FREE")
                    .build();
        }

        Long paymentId = extractPaymentId(orderCode);
        if (paymentId == null) {
            return SubscriptionOrderStatusResponse.builder()
                    .orderCode(orderCode)
                    .status("FAILED")
                    .planName("FREE")
                    .build();
        }

        Optional<SubscriptionPayment> paymentOpt = tenantId != null
                ? paymentRepository.findByIdAndTenantId(paymentId, tenantId)
                : paymentRepository.findById(paymentId);

        if (paymentOpt.isEmpty()) {
            return SubscriptionOrderStatusResponse.builder()
                    .orderCode(orderCode)
                    .status("FAILED")
                    .planName("FREE")
                    .build();
        }

        SubscriptionPayment payment = paymentOpt.get();
        String status = "PENDING";
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            status = "PAID";
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            status = "FAILED";
        } else if (payment.getCreatedAt() != null && payment.getCreatedAt().isBefore(Instant.now().minus(24, ChronoUnit.HOURS))) {
            status = "EXPIRED";
        }

        return SubscriptionOrderStatusResponse.builder()
                .orderCode(orderCode)
                .status(status)
                .planName(status.equals("PAID") ? "PRO" : "FREE")
                .paidAt(payment.getPaidAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionInvoiceDto> getTenantInvoices(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        return invoiceRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, InvoiceStatus.PAID).stream()
                .map(inv -> SubscriptionInvoiceDto.builder()
                        .id(inv.getId())
                        .invoiceNumber("INV-" + inv.getId())
                        .paymentCode(inv.getPaymentCode())
                        .gatewayTransactionId(inv.getGatewayTransactionId())
                        .paymentMethod("Chuyển khoản SePay (VietQR)")
                        .period((inv.getBillingPeriodStart() != null ? inv.getBillingPeriodStart().toString() : "")
                                + " - " + (inv.getBillingPeriodEnd() != null ? inv.getBillingPeriodEnd().toString() : ""))
                        .amount(inv.getAmount())
                        .status(inv.getStatus() != null ? inv.getStatus().name() : "PAID")
                        .dueDate(inv.getDueDate())
                        .paidAt(inv.getPaidAt() != null ? inv.getPaidAt() : inv.getCreatedAt())
                        .createdAt(inv.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionSummaryResponse getSubscriptionSummary(Long tenantId) {
        if (tenantId == null) {
            return SubscriptionSummaryResponse.builder()
                    .planName("FREE")
                    .isPro(false)
                    .status("FREE")
                    .periodStart(null)
                    .periodEnd(null)
                    .daysRemaining(0L)
                    .canRenewEarly(false)
                    .currentInvoiceId(null)
                    .build();
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException("Tenant not found", HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND"));

        boolean isProTenant = isProTenant(tenant);
        Optional<TenantInvoice> latestInvoiceOpt = invoiceRepository
                .findFirstByTenantIdAndStatusOrderByBillingPeriodEndDesc(tenantId, InvoiceStatus.PAID);

        if (!isProTenant || latestInvoiceOpt.isEmpty()) {
            return SubscriptionSummaryResponse.builder()
                    .planName("FREE")
                    .isPro(false)
                    .status("FREE")
                    .periodStart(null)
                    .periodEnd(null)
                    .daysRemaining(0L)
                    .canRenewEarly(false)
                    .currentInvoiceId(null)
                    .build();
        }

        TenantInvoice latestInvoice = latestInvoiceOpt.get();
        LocalDate now = LocalDate.now();
        LocalDate periodEnd = latestInvoice.getBillingPeriodEnd();
        LocalDate periodStart = latestInvoice.getBillingPeriodStart();

        long daysRemaining = periodEnd != null ? ChronoUnit.DAYS.between(now, periodEnd) : 0L;
        boolean isExpired = daysRemaining < 0;

        boolean isProActive = isProTenant && !isExpired;
        boolean canRenewEarly = isProActive && daysRemaining <= 5;
        String status = isExpired ? "EXPIRED" : (daysRemaining <= 5 ? "EXPIRING_SOON" : "ACTIVE");

        return SubscriptionSummaryResponse.builder()
                .planName(isProActive ? "PRO" : "FREE")
                .isPro(isProActive)
                .status(status)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .daysRemaining(Math.max(0L, daysRemaining))
                .canRenewEarly(canRenewEarly)
                .currentInvoiceId(latestInvoice.getId())
                .build();
    }

    @Override
    @Transactional
    public boolean processWebhook(String rawBody, String signature, String timestamp) {
        if (!signatureValidator.validate(rawBody, signature, timestamp, sepayProperties.getWebhookSecret())) {
            log.warn("SePay webhook rejected: signature verification failed");
            return false;
        }

        SepayWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, SepayWebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse SePay webhook payload: {}", e.getMessage());
            return false;
        }

        if (payload == null) {
            return false;
        }

        // Set super_admin role temporarily to bypass RLS for system webhook processing
        String previousRole = TenantContext.getCurrentRole();
        TenantContext.setCurrentRole("super_admin");
        try {
            return handleVerifiedWebhook(payload);
        } finally {
            TenantContext.setCurrentRole(previousRole);
        }
    }

    private boolean handleVerifiedWebhook(SepayWebhookPayload payload) {
        if (entityManager != null) {
            RlsSession.set(entityManager, "app.user_role", "super_admin");
        }

        if (payload.getId() == null) {
            log.warn("Webhook rejected: missing payload id");
            return false;
        }

        String gatewayTxnId = payload.getId().toString();
        Optional<SubscriptionPayment> existingPayment = paymentRepository.findByGatewayTransactionId(gatewayTxnId);
        if (existingPayment.isPresent()) {
            log.info("Webhook duplicate received for transaction id {}. Returning previous verdict.", gatewayTxnId);
            return existingPayment.get().getStatus() == PaymentStatus.SUCCESS;
        }

        if (payload.getTransferType() != null && !"in".equalsIgnoreCase(payload.getTransferType())) {
            log.info("Webhook skipped: transferType is not 'in' ({})", payload.getTransferType());
            return false;
        }

        String orderCode = payload.getCode();
        if (orderCode == null || orderCode.isBlank()) {
            orderCode = extractCodeFromContent(payload.getContent());
        }

        if (orderCode == null || orderCode.isBlank()) {
            log.warn("Webhook transaction {} has no payment code", payload.getId());
            return false;
        }

        final String finalOrderCode = orderCode;

        Long paymentId = extractPaymentId(orderCode);
        if (paymentId == null) {
            log.warn("Webhook transaction {} could not extract payment ID from code '{}'", payload.getId(), orderCode);
            return false;
        }

        Optional<SubscriptionPayment> paymentOpt = paymentRepository.findById(paymentId);
        if (paymentOpt.isEmpty()) {
            log.warn("Webhook transaction {} references non-existent payment ID {}", payload.getId(), paymentId);
            return false;
        }

        SubscriptionPayment payment = paymentOpt.get();

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Payment ID {} already marked SUCCESS", paymentId);
            return true;
        }

        BigDecimal transferAmount = payload.getTransferAmount() != null ? payload.getTransferAmount() : BigDecimal.ZERO;
        BigDecimal expectedAmount = payment.getAmount() != null ? payment.getAmount() : sepayProperties.getProPlanPrice();

        if (transferAmount.compareTo(expectedAmount) < 0) {
            log.warn("Webhook transaction {} underpayment: transferred {}, expected {}",
                    payload.getId(), transferAmount, expectedAmount);
            payment.setIsAnomaly(true);
            payment.setGatewayTransactionId(gatewayTxnId);
            payment.setSignatureValid(true);
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            return false;
        }

        // Upgrade tenant to PRO
        SubscriptionPlan proPlan = subscriptionPlanRepository.findByName("PRO")
                .or(() -> subscriptionPlanRepository.findByName("Pro"))
                .orElse(null);

        // Option 2 (Stripe/Shopify model): Generate official PAID invoice upon payment confirmation
        TenantInvoice invoice;
        if (payment.getInvoiceId() != null) {
            invoice = invoiceRepository.findById(payment.getInvoiceId())
                    .orElseGet(() -> createPaidInvoice(payment, proPlan, gatewayTxnId, finalOrderCode));
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setGatewayTransactionId(gatewayTxnId);
            invoice.setPaidAt(Instant.now());
            if (invoice.getPaymentCode() == null) {
                invoice.setPaymentCode(finalOrderCode);
            }
            invoice = invoiceRepository.save(invoice);
        } else {
            invoice = createPaidInvoice(payment, proPlan, gatewayTxnId, finalOrderCode);
        }

        // Complete payment successfully
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(Instant.now());
        payment.setGatewayTransactionId(gatewayTxnId);
        payment.setSignatureValid(true);
        payment.setIsAnomaly(false);
        if (invoice != null && invoice.getId() != null) {
            payment.setInvoiceId(invoice.getId());
        }
        paymentRepository.save(payment);

        if (proPlan != null) {
            tenantRepository.findById(payment.getTenantId()).ifPresent(tenant -> {
                TenantSubscriptionHistory history = new TenantSubscriptionHistory();
                history.setTenantId(tenant.getId());
                history.setOldPlanId(tenant.getPlanId());
                history.setNewPlanId(proPlan.getId());
                history.setChangedBy(payment.getConfirmedBy());
                history.setChangedAt(Instant.now());
                historyRepository.save(history);

                tenant.setPlanId(proPlan.getId());
                tenant.setPlanType("PRO");
                tenantRepository.save(tenant);
                log.info("Tenant {} successfully upgraded to PRO plan via SePay payment {}", tenant.getId(), payment.getId());
            });
        }

        return true;
    }

    @Override
    @Transactional
    public boolean simulatePaymentSuccess(Long tenantId, String orderCode) {
        if (entityManager != null) {
            RlsSession.set(entityManager, "app.user_role", "super_admin");
        }
        Long paymentId = extractPaymentId(orderCode);
        if (paymentId == null) {
            return false;
        }

        Optional<SubscriptionPayment> paymentOpt = tenantId != null
                ? paymentRepository.findByIdAndTenantId(paymentId, tenantId)
                : paymentRepository.findById(paymentId);

        if (paymentOpt.isEmpty()) {
            return false;
        }

        SubscriptionPayment payment = paymentOpt.get();
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return true;
        }

        SubscriptionPlan proPlan = subscriptionPlanRepository.findByName("PRO")
                .or(() -> subscriptionPlanRepository.findByName("Pro"))
                .orElse(null);

        String simTxnId = "SIM-" + System.currentTimeMillis();

        // Option 2 (Stripe/Shopify model): Generate official PAID invoice upon payment confirmation
        TenantInvoice invoice;
        if (payment.getInvoiceId() != null) {
            invoice = invoiceRepository.findById(payment.getInvoiceId())
                    .orElseGet(() -> createPaidInvoice(payment, proPlan, simTxnId, orderCode));
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setGatewayTransactionId(simTxnId);
            invoice.setPaidAt(Instant.now());
            if (invoice.getPaymentCode() == null) {
                invoice.setPaymentCode(orderCode);
            }
            invoice = invoiceRepository.save(invoice);
        } else {
            invoice = createPaidInvoice(payment, proPlan, simTxnId, orderCode);
        }

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(Instant.now());
        payment.setGatewayTransactionId(simTxnId);
        payment.setSignatureValid(true);
        payment.setIsAnomaly(false);
        if (invoice != null && invoice.getId() != null) {
            payment.setInvoiceId(invoice.getId());
        }
        paymentRepository.save(payment);

        if (proPlan != null) {
            tenantRepository.findById(payment.getTenantId()).ifPresent(tenant -> {
                TenantSubscriptionHistory history = new TenantSubscriptionHistory();
                history.setTenantId(tenant.getId());
                history.setOldPlanId(tenant.getPlanId());
                history.setNewPlanId(proPlan.getId());
                history.setChangedBy(payment.getConfirmedBy());
                history.setChangedAt(Instant.now());
                historyRepository.save(history);

                tenant.setPlanId(proPlan.getId());
                tenant.setPlanType("PRO");
                tenantRepository.save(tenant);
                log.info("Tenant {} successfully upgraded to PRO plan via simulation on orderCode {}", tenant.getId(), orderCode);
            });
        }

        return true;
    }

    private TenantInvoice createPaidInvoice(SubscriptionPayment payment, SubscriptionPlan plan) {
        String orderCode = SepayVietQrHelper.generateOrderCode(payment.getTenantId(), payment.getId());
        return createPaidInvoice(payment, plan, payment.getGatewayTransactionId(), orderCode);
    }

    private TenantInvoice createPaidInvoice(SubscriptionPayment payment, SubscriptionPlan plan, String gatewayTxnId, String orderCode) {
        TenantInvoice invoice = new TenantInvoice();
        invoice.setTenantId(payment.getTenantId());
        invoice.setPlanId(plan != null ? plan.getId() : null);
        invoice.setAmount(payment.getAmount());
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setDueDate(LocalDate.now());
        invoice.setPaidAt(payment.getPaidAt() != null ? payment.getPaidAt() : Instant.now());

        if (orderCode == null || orderCode.isBlank()) {
            orderCode = SepayVietQrHelper.generateOrderCode(payment.getTenantId(), payment.getId());
        }
        invoice.setPaymentCode(orderCode);
        invoice.setGatewayTransactionId(gatewayTxnId != null ? gatewayTxnId : payment.getGatewayTransactionId());

        Optional<TenantInvoice> latestActivePaid = invoiceRepository
                .findFirstByTenantIdAndStatusOrderByBillingPeriodEndDesc(payment.getTenantId(), InvoiceStatus.PAID);

        if (latestActivePaid.isPresent()
                && latestActivePaid.get().getBillingPeriodEnd() != null
                && latestActivePaid.get().getBillingPeriodEnd().isAfter(LocalDate.now())) {
            LocalDate nextStart = latestActivePaid.get().getBillingPeriodEnd();
            invoice.setBillingPeriodStart(nextStart);
            invoice.setBillingPeriodEnd(nextStart.plusMonths(1));
        } else {
            invoice.setBillingPeriodStart(LocalDate.now());
            invoice.setBillingPeriodEnd(LocalDate.now().plusMonths(1));
        }

        return invoiceRepository.save(invoice);
    }

    private Long extractPaymentId(String orderCode) {
        if (orderCode == null) return null;
        String cleanCode = orderCode.trim().toUpperCase();

        Matcher standardMatcher = ORDER_CODE_PATTERN.matcher(cleanCode);
        if (standardMatcher.find()) {
            try {
                return Long.parseLong(standardMatcher.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Matcher fallbackMatcher = FALLBACK_PRO_PATTERN.matcher(cleanCode);
        if (fallbackMatcher.find()) {
            String digits = fallbackMatcher.group().substring(3);
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    private boolean isProTenant(Tenant tenant) {
        if (tenant == null) {
            return false;
        }
        if (tenant.getPlanId() != null) {
            if (Long.valueOf(2L).equals(tenant.getPlanId())) {
                return true;
            }
            Optional<SubscriptionPlan> planOpt = subscriptionPlanRepository.findById(tenant.getPlanId());
            if (planOpt.isPresent() && "PRO".equalsIgnoreCase(planOpt.get().getName())) {
                return true;
            }
        }
        return "PRO".equalsIgnoreCase(tenant.getPlanType());
    }

    private String extractCodeFromContent(String content) {
        if (content == null || content.isBlank()) return null;
        Matcher standardMatcher = ORDER_CODE_PATTERN.matcher(content.toUpperCase());
        if (standardMatcher.find()) {
            return standardMatcher.group();
        }
        Matcher fallbackMatcher = FALLBACK_PRO_PATTERN.matcher(content.toUpperCase());
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group();
        }
        return null;
    }
}
