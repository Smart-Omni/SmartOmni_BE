package com.smartomni.integration.client;

import com.smartomni.integration.dto.MarketplaceProductDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Legacy marketplace contract used by the Shopee stub. TikTok Shop uses the
 * signed, token-aware client in {@code com.smartomni.integration.client.tiktok}.
 */
public interface MarketplaceClient {

    /** UC-41 (FR-082): lay toan bo san pham dang ban, ho tro phan trang (FR-084). */
    List<MarketplaceProductDto> getProductList(String appKey, String appSecretDecrypted, int page, int pageSize);

    /** UC-43 (FR-090): lay gia hien tai cua 1 san pham theo platformItemId. */
    BigDecimal getCurrentPrice(String appKey, String appSecretDecrypted, String platformItemId);

    /** UC-40 (FR-075): day cap nhat ton kho ngoc len san. */
    void updateStock(String appKey, String appSecretDecrypted, String platformItemId, int quantity);

    /** UC-34 (FR-063): lay danh sach don hang trong khoang thoi gian (Polling). */
    // List<MarketplaceOrderDto> getOrderList(String appKey, String appSecretDecrypted, Instant from, Instant to);
}
