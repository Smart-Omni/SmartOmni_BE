package com.smartomni.integration.dto.tiktok;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Catalog item view returned by {@code GET /api/v1/tiktok/catalog} (D8),
 * combining TikTok product/SKU data with SmartOmni internal SKU mapping.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TiktokCatalogItemDto {

    private String tiktokProductId;
    private String tiktokSkuId;
    private String sellerSku;
    private String productName;
    private BigDecimal currentPrice;
    private String currency;
    private int stock;
    private Long localProductId;
    private Long localSkuId;
    private String localSkuCode;
    private boolean mapped;
}
