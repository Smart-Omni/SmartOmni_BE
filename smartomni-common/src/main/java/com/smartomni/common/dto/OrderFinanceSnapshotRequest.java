package com.smartomni.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal DTO used to persist normalized per-order marketplace finance snapshots in order-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderFinanceSnapshotRequest {
    private Long tenantId;
    private Long connectionId;
    private String platform; // SHOPEE | TIKTOK_SHOP
    private String platformOrderId;
    private String currency;
    private String settlementStatus; // SETTLED | UNSETTLED | RELEASED | PENDING | ESTIMATED
    private boolean estimated;
    private String statementId;
    private Instant statementTime;
    private BigDecimal buyerPayment;
    private BigDecimal merchandiseValue;
    private BigDecimal commissionFee;
    private BigDecimal serviceFee;
    private BigDecimal transactionFee;
    private BigDecimal taxAmount;
    private BigDecimal platformFees;
    private BigDecimal actualShippingFee;
    private BigDecimal buyerPaidShippingFee;
    private BigDecimal platformShippingRebate;
    private BigDecimal sellerShippingDiscount;
    private BigDecimal sellerShippingCost;
    private BigDecimal sellerReturnRefund;
    private BigDecimal reverseShippingFee;
    private BigDecimal otherAdjustments;
    private BigDecimal sellerSettlement;
    private String sourceEndpoint;
    private Instant sourceWatermark;
}
