package com.smartomni.catalog.entity;

import org.hibernate.annotations.JdbcType;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * UC-09: Cap nhat link san pham tren san TMDT (thu cong).
 * UC-42/UC-43: cung la noi luu ket qua tu dong nhap san pham / dong bo gia.
 */
@Getter
@Setter
@Entity
@Table(name = "product_marketplace_links", uniqueConstraints = @UniqueConstraint(columnNames = {"sku_id", "connection_id"}))
public class ProductMarketplaceLink extends BaseTenantEntity {

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "platform_type")
    private Platform platform;

    @Column(name = "platform_item_id")
    private String platformItemId;

    @Column(name = "platform_product_url")
    private String platformProductUrl;

    @Column(name = "platform_price")
    private BigDecimal platformPrice; // FR-091: gia rieng theo tung kenh

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    // V10: platform identity of the SKU this link maps (connection + external SKU id is unique).
    @Column(name = "external_product_id")
    private String externalProductId;

    @Column(name = "external_sku_id")
    private String externalSkuId;

    @Column(name = "seller_sku")
    private String sellerSku;

    @Column(name = "currency")
    private String currency;

    @Column(name = "external_status")
    private String externalStatus;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    public enum Platform { SHOPEE, TIKTOK_SHOP, STOREFRONT }
}
