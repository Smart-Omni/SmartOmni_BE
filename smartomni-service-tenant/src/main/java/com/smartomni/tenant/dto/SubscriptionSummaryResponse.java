package com.smartomni.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionSummaryResponse {

    private String planName;
    private boolean isPro;
    private String status;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Long daysRemaining;
    private boolean canRenewEarly;
    private Long currentInvoiceId;
}
