package com.smartomni.catalog.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("UnifiedCatalogService: link sản phẩm TikTok từ TIKTOK_PRODUCT_URL_TEMPLATE")
class UnifiedCatalogServiceTiktokUrlTest {

    private static final String TEMPLATE = "https://smartomni-tiktok-simulator-ten.vercel.app/product/{productId}";

    @Test
    @DisplayName("Có template: thay {productId} bằng id sản phẩm")
    void replacesProductId() {
        assertEquals("https://smartomni-tiktok-simulator-ten.vercel.app/product/7500009085405800869",
                UnifiedCatalogService.tiktokProductUrl(TEMPLATE, "7500009085405800869"));
        assertEquals("https://smartomni-tiktok-simulator-ten.vercel.app/product/42",
                UnifiedCatalogService.tiktokProductUrl(" " + TEMPLATE + " ", " 42 "));
    }

    @Test
    @DisplayName("Template trống: không dựng link (null), không trỏ về domain cũ")
    void emptyTemplateGivesNoLink() {
        assertNull(UnifiedCatalogService.tiktokProductUrl("", "42"));
        assertNull(UnifiedCatalogService.tiktokProductUrl(null, "42"));
        assertNull(UnifiedCatalogService.tiktokProductUrl("  ", "42"));
    }

    @Test
    @DisplayName("Thiếu id sản phẩm: null")
    void missingProductIdGivesNoLink() {
        assertNull(UnifiedCatalogService.tiktokProductUrl(TEMPLATE, null));
        assertNull(UnifiedCatalogService.tiktokProductUrl(TEMPLATE, ""));
    }
}
