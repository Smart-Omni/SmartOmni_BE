package com.smartomni.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionOrderStatusResponse {

    private String orderCode;
    private String status; // PENDING, PAID, FAILED, EXPIRED
    private String planName;
    private Instant paidAt;
}
