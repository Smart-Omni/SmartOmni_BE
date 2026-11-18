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

        SubscriptionPlan proPlan = subscriptionPlanRepository.findByName("PRO")
                .or(() -> subscriptionPlanRepository.findByName("Pro"))
                .orElseThrow(() -> new BusinessException("Gói dịch vụ PRO chưa được cấu hình", HttpStatus.NOT_FOUND, "SUBSCRIPTION_PLAN_NOT_FOUND"));

        BigDecimal amount = proPlan.getPrice() != null && proPlan.getPrice().compareTo(BigDecimal.ZERO) > 0
                ? proPlan.getPrice()
                : sepayProperties.getProPlanPrice();

        // Reuse existing PENDING invoice to prevent duplicate billing entries for the same period
        TenantInvoice invoice = invoiceRepository
                .findFirstByTenantIdAndStatusOrderByCreatedAtDesc(tenant.getId(), InvoiceStatus.PENDING)
                .orElseGet(() -> {
                    TenantInvoice newInvoice = new TenantInvoice();
                    newInvoice.setTenantId(tenant.getId());
                    newInvoice.setPlanId(proPlan.getId());
                    newInvoice.setAmount(amount);
                    newInvoice.setStatus(InvoiceStatus.PENDING);
                    newInvoice.setBillingPeriodStart(LocalDate.now());
                    newInvoice.setBillingPeriodEnd(LocalDate.now().plusMonths(1));
                    newInvoice.setDueDate(LocalDate.now().plusDays(1));
                    return invoiceRepository.save(newInvoice);
                });

        // Reuse active PENDING payment attempt if available, otherwise create a new one
        SubscriptionPayment payment = paymentRepository
                .findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(invoice.getId(), PaymentStatus.PENDING)
                .orElseGet(() -> {
                    SubscriptionPayment newPayment = new SubscriptionPayment();
                    newPayment.setInvoiceId(invoice.getId());
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

        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

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
        return invoiceRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(inv -> SubscriptionInvoiceDto.builder()
                        .id(inv.getId())
                        .period((inv.getBillingPeriodStart() != null ? inv.getBillingPeriodStart().toString() : "")
                                + " - " + (inv.getBillingPeriodEnd() != null ? inv.getBillingPeriodEnd().toString() : ""))
                        .amount(inv.getAmount())
                        .status(inv.getStatus() != null ? inv.getStatus().name() : "PENDING")
                        .dueDate(inv.getDueDate())
                        .createdAt(inv.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
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

        // Complete payment successfully
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(Instant.now());
        payment.setGatewayTransactionId(gatewayTxnId);
        payment.setSignatureValid(true);
        payment.setIsAnomaly(false);
        paymentRepository.save(payment);

        if (payment.getInvoiceId() != null) {
            invoiceRepository.findById(payment.getInvoiceId()).ifPresent(inv -> {
                inv.setStatus(InvoiceStatus.PAID);
                invoiceRepository.save(inv);
            });
        }

        // Upgrade tenant to PRO
        SubscriptionPlan proPlan = subscriptionPlanRepository.findByName("PRO")
                .or(() -> subscriptionPlanRepository.findByName("Pro"))
                .orElse(null);

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
