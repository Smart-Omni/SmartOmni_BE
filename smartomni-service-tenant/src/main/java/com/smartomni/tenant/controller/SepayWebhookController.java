package com.smartomni.tenant.controller;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.tenant.config.SepayProperties;
import com.smartomni.tenant.dto.SepayWebhookResponse;
import com.smartomni.tenant.service.SubscriptionService;
import com.smartomni.tenant.util.SepaySignatureValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SepayWebhookController {

    private final SubscriptionService subscriptionService;
    private final SepaySignatureValidator signatureValidator;
    private final SepayProperties sepayProperties;

    @PostMapping({"/webhook/sepay", "/api/v1/payments/sepay/webhook"})
    public ResponseEntity<SepayWebhookResponse> handleWebhook(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "X-SePay-Signature", required = false) String signature,
            @RequestHeader(value = "X-SePay-Timestamp", required = false) String timestamp
    ) {
        log.info("Received SePay webhook: signature={}, timestamp={}", signature, timestamp);

        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new SepayWebhookResponse(false, "Payload body is empty"));
        }

        // Validate signature first. If invalid, return 401 Unauthorized
        if (!signatureValidator.validate(rawBody, signature, timestamp, sepayProperties.getWebhookSecret())) {
            log.warn("SePay webhook unauthorized: bad signature or invalid timestamp");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new SepayWebhookResponse(false, "Invalid or expired signature"));
        }

        // Process verified webhook as super_admin to allow cross-tenant updates under RLS
        TenantContext.setCurrentRole("super_admin");
        try {
            boolean verified = subscriptionService.processWebhook(rawBody, signature, timestamp);

            if (verified) {
                return ResponseEntity.ok(new SepayWebhookResponse(true, "Transaction verified and processed"));
            } else {
                // SePay protocol: 200 OK with success: false for unmatched or underpaid transfers
                return ResponseEntity.ok(new SepayWebhookResponse(false, "Transaction unmatched, underpaid, or already handled"));
            }
        } finally {
            TenantContext.clear();
        }
    }
}
