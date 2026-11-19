package com.smartomni.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCheckoutResponse {

    private String orderCode;
    private BigDecimal amount;
    private String qrUrl;
    private String accountNumber;
    private String bankId;
    private String accountHolder;
    private String memo;
    private String planName;
    private Instant expiresAt;
}
