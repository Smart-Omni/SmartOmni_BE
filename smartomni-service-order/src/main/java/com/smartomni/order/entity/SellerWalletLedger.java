package com.smartomni.order.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ghi nhận doanh thu / payout tự động vào ví nhà bán hàng khi đơn hàng COMPLETED (Luồng 3b).
 * Bảng: seller_wallet_ledger.
 */
@Getter
@Setter
@Entity
@Table(name = "seller_wallet_ledger", uniqueConstraints = @UniqueConstraint(columnNames = {"order_id"}))
public class SellerWalletLedger extends BaseTenantEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "gross_amount")
    private BigDecimal grossAmount;

    @Column(name = "platform_fee")
    private BigDecimal platformFee;

    @Column(name = "freeship_fee")
    private BigDecimal freeshipFee;

    @Column(name = "net_payout")
    private BigDecimal netPayout;

    @Column(name = "recorded_at")
    private Instant recordedAt = Instant.now();
}
