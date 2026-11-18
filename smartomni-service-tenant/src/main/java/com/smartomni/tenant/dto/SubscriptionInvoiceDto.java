package com.smartomni.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionInvoiceDto {

    private Long id;
    private String period;
    private BigDecimal amount;
    private String status;
    private LocalDate dueDate;
    private Instant createdAt;
}
