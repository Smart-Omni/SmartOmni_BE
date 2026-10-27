package com.smartomni.order.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;

import java.time.Instant;

/**
 * Lịch sử thay đổi trạng thái đơn hàng (order_status_history).
 * Source: manager | marketplace_webhook | system.
 */
@Getter
@Setter
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory extends BaseTenantEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(name = "status", columnDefinition = "order_status")
    private Order.OrderStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(name = "source", nullable = false, columnDefinition = "order_change_source")
    private OrderChangeSource source;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at")
    private Instant changedAt = Instant.now();

    public enum OrderChangeSource {
        MANAGER,
        MARKETPLACE_WEBHOOK,
        SYSTEM
    }
}
