package com.smartomni.order.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Normalized per-order marketplace financial snapshot (Shopee escrow detail & TikTok statement transactions).
 */
@Getter
@Setter
@Entity
@Table(name = "order_finance_snapshots",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "platform", "platform_order_id"}))
public class OrderFinanceSnapshot extends BaseTenantEntity {

    @Column(name = "connection_id")
    private Long connectionId;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "platform_type")
    private Order.Platform platform;

    @Column(name = "platform_order_id", nullable = false)
    private String platformOrderId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false, length = 16)
    private String currency = "VND";

    @Column(name = "settlement_status", nullable = false, length = 32)
    private String settlementStatus = "ESTIMATED";

    @Column(nullable = false)
    private boolean estimated = false;

    @Column(name = "statement_id", length = 128)
    private String statementId;

    @Column(name = "statement_time")
    private Instant statementTime;

    @Column(name = "buyer_payment")
    private BigDecimal buyerPayment;

    @Column(name = "merchandise_value")
    private BigDecimal merchandiseValue;

    @Column(name = "commission_fee")
    private BigDecimal commissionFee;

    @Column(name = "service_fee")
    private BigDecimal serviceFee;

    @Column(name = "transaction_fee")
    private BigDecimal transactionFee;

    @Column(name = "tax_amount")
    private BigDecimal taxAmount;

    @Column(name = "platform_fees")
    private BigDecimal platformFees;

    @Column(name = "actual_shipping_fee")
    private BigDecimal actualShippingFee;

    @Column(name = "buyer_paid_shipping_fee")
    private BigDecimal buyerPaidShippingFee;

    @Column(name = "platform_shipping_rebate")
    private BigDecimal platformShippingRebate;

    @Column(name = "seller_shipping_discount")
    private BigDecimal sellerShippingDiscount;

    @Column(name = "seller_shipping_cost")
    private BigDecimal sellerShippingCost;

    @Column(name = "seller_return_refund")
    private BigDecimal sellerReturnRefund;

    @Column(name = "reverse_shipping_fee")
    private BigDecimal reverseShippingFee;

    @Column(name = "other_adjustments")
    private BigDecimal otherAdjustments;

    @Column(name = "seller_settlement")
    private BigDecimal sellerSettlement;

    @Column(name = "source_endpoint", length = 255)
    private String sourceEndpoint;

    @Column(name = "source_watermark")
    private Instant sourceWatermark;
}
