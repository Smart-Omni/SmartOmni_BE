package com.smartomni.order.entity;

import org.hibernate.annotations.JdbcType;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * UC-10: Theo doi & xu ly don hang.
 * Unique constraint (tenant_id, platform, platform_order_id) chinh la co che
 * chong trung lap (idempotency) khi Webhook va Polling cung ghi nhan 1 don (FR-025).
 */
@Getter
@Setter
@Entity
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "connection_id", "platform_order_id"}))
public class Order extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "platform_type")
    private Platform platform;

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "platform_order_id")
    private String platformOrderId;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "order_status")
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @Column(name = "shipping_method")
    private String shippingMethod;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "courier_scanned_at")
    private Instant courierScannedAt;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "order_source")
    private OrderSource source; // WEBHOOK | POLLING | MANUAL - FR-025

    @Column(name = "return_reason", length = 255)
    private String returnReason;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "evidence_urls", columnDefinition = "TEXT")
    private String evidenceUrls;

    @Column(name = "restock_status", length = 50)
    private String restockStatus;

    public enum Platform { SHOPEE, TIKTOK_SHOP }
    public enum OrderStatus { PENDING, TO_SHIP, SHIPPED, DELIVERING, COMPLETED, CANCELLED, RETURNED, ON_HOLD }
    public enum OrderSource { WEBHOOK, POLLING, MANUAL }
}
