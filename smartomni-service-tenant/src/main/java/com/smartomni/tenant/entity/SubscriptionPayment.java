package com.smartomni.tenant.entity;

import com.smartomni.common.entity.BaseEntity;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "subscription_payments")
public class SubscriptionPayment extends BaseEntity {

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "gateway_transaction_id", unique = true)
    private String gatewayTransactionId;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    @Column(name = "is_anomaly")
    private Boolean isAnomaly = false;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "payment_status")
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;
}
