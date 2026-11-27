package com.smartomni.catalog.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public storefront projection of a product. A whitelist: no tenant id, base price, SKU code, stock quantity,
 * connection or marketplace ids, so it can be served to anonymous shoppers.
 */
@Value
@Builder
public class PublicStorefrontProductDto {
    Long id;
    String name;
    String description;
    String imageUrl;
    List<Variant> variants;

    @Value
    @Builder
    public static class Variant {
        Long id;
        String variantName;
        List<Offer> offers;
    }

    /** One marketplace listing a shopper can buy on. {@code platform} is {@code shopee} or {@code tiktok_shop}. */
    @Value
    @Builder
    public static class Offer {
        String platform;
        BigDecimal price;
        String currency;
        boolean available;
        String url;
    }
}
