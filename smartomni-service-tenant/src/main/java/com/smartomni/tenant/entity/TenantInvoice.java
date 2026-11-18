package com.smartomni.tenant.entity;

import com.smartomni.common.entity.BaseEntity;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "tenant_invoices")
public class TenantInvoice extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "billing_period_start")
    private LocalDate billingPeriodStart;

    @Column(name = "billing_period_end")
    private LocalDate billingPeriodEnd;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "invoice_status")
    private InvoiceStatus status = InvoiceStatus.PENDING;

    @Column(name = "due_date")
    private LocalDate dueDate;
}
