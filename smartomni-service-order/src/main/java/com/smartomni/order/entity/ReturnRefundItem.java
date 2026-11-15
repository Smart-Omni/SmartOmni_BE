package com.smartomni.order.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Line item in a return & refund request (WF-05, DS-09).
 */
@Getter
@Setter
@Entity
@Table(name = "return_refund_items")
public class ReturnRefundItem extends BaseTenantEntity {

    @Column(name = "return_refund_id", nullable = false)
    private Long returnRefundId;

    @Column(name = "sku_id")
    private Long skuId;

    @Column(name = "external_item_id")
    private String externalItemId;

    @Column(name = "item_name")
    private String itemName;

    @Column(name = "quantity_returned", nullable = false)
    private Integer quantityReturned = 1;

    @Column(name = "quantity_inspected")
    private Integer quantityInspected = 0;

    @Column(name = "refund_amount")
    private BigDecimal refundAmount;
}
