package com.smartomni.integration.client.shopee;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShopeeApiSignerTest {

    private static final String PARTNER_KEY = "shopee_mock_secret_key_2026";
    private static final long PARTNER_ID = 1001141L;
    private static final long TIMESTAMP = 1714000000L;
    private static final long SHOP_ID = 727720655L;
    private static final String ACCESS_TOKEN = "test_access_token_123";

    @Test
    void publicBaseStringConcatenatesPartnerIdPathTimestamp() {
        String base = ShopeeApiSigner.publicBaseString(PARTNER_ID, "/api/v2/auth/token/get", TIMESTAMP);
        assertEquals("1001141/api/v2/auth/token/get1714000000", base);
    }

    @Test
    void shopBaseStringConcatenatesPartnerIdPathTimestampAccessTokenShopId() {
        String base = ShopeeApiSigner.shopBaseString(PARTNER_ID, "/api/v2/shop/get_shop_info", TIMESTAMP, ACCESS_TOKEN, SHOP_ID);
        assertEquals("1001141/api/v2/shop/get_shop_info1714000000test_access_token_123727720655", base);
    }

    @Test
    void merchantBaseStringConcatenatesPartnerIdPathTimestampAccessTokenMerchantId() {
        String base = ShopeeApiSigner.merchantBaseString(PARTNER_ID, "/api/v2/merchant/get_info", TIMESTAMP, ACCESS_TOKEN, 888999L);
        assertEquals("1001141/api/v2/merchant/get_info1714000000test_access_token_123888999", base);
    }

    @Test
    void signProducesLowercaseHexDigest() {
        String sign = ShopeeApiSigner.signPublic(PARTNER_KEY, PARTNER_ID, "/api/v2/auth/token/get", TIMESTAMP);
        assertNotNull(sign);
        assertEquals(64, sign.length(), "HMAC-SHA256 hex string should be 64 characters");
        assertEquals(sign.toLowerCase(), sign, "Signature must be all lowercase hex");
        // Verify deterministic calculation
        String expectedSign = ShopeeApiSigner.sign(PARTNER_KEY, "1001141/api/v2/auth/token/get1714000000");
        assertEquals(expectedSign, sign);
    }

    @Test
    void signThrowsOnBlankKey() {
        assertThrows(IllegalArgumentException.class, () ->
                ShopeeApiSigner.sign("", "some-base-string"));
        assertThrows(IllegalArgumentException.class, () ->
                ShopeeApiSigner.sign(null, "some-base-string"));
    }
}
