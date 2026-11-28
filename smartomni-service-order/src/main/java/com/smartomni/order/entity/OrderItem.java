package com.smartomni.order.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseTenantEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "sku_id")
    private Long skuId;

    private Integer quantity;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "external_sku_id", length = 128)
    private String externalSkuId;

    @Column(name = "seller_sku", length = 128)
    private String sellerSku;

    @Column(name = "product_name", length = 512)
    private String productName;

    @Column(name = "original_price")
    private BigDecimal originalPrice;

    @Column(name = "seller_discount")
    private BigDecimal sellerDiscount;

    @Column(name = "platform_discount")
    private BigDecimal platformDiscount;

    @Column(name = "merchandise_value")
    private BigDecimal merchandiseValue;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;
}
