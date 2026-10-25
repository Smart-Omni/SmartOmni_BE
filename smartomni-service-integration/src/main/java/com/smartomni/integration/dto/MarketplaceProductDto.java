package com.smartomni.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Cau truc san pham chuan hoa tra ve tu API cua Shopee/TikTok Shop (UC-41). */
@Getter
@Setter
public class MarketplaceProductDto {
    private String platformItemId;
    /** Platform product status as reported (e.g. ACTIVATE); kept verbatim. */
    private String platformStatus;
    private String name;
    private String description;
    private List<String> imageUrls;
    private List<VariantDto> variants;

    @Getter
    @Setter
    public static class VariantDto {
        /** Platform SKU id (string, owned by the platform). Null for platforms that have none. */
        private String externalSkuId;
        /** Seller SKU exactly as the platform reports it; mapped to the internal SKU code. */
        private String sellerSku;
        private String currency;
        /** Per-warehouse stock, kept for the inventory phase. */
        private List<StockDto> stock;
        private String skuCode;
        private String variantName;
        private BigDecimal price;
    }

    @Getter
    @Setter
    public static class StockDto {
        private String warehouseId;
        private Integer quantity;
    }
}
