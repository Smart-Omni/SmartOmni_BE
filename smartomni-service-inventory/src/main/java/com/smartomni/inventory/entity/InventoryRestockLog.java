package com.smartomni.inventory.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Log of applied inventory restock operations for idempotency protection (WF-05, DS-09).
 */
@Getter
@Setter
@Entity
@Table(name = "inventory_restock_logs")
public class InventoryRestockLog extends BaseTenantEntity {

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;
}
