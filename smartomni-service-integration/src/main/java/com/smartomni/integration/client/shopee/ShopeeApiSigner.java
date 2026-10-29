package com.smartomni.integration.client.shopee;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Shopee OpenAPI v2 request signing using HMAC-SHA256.
 *
 * Base string specifications:
 * - Public API: partner_id + path + timestamp
 * - Shop API: partner_id + path + timestamp + access_token + shop_id
 * - Merchant API: partner_id + path + timestamp + access_token + merchant_id
 *
 * Signature: lowercase_hex(HMAC_SHA256(key = partner_key, message = base_string))
 */
public final class ShopeeApiSigner {

    private ShopeeApiSigner() {
    }

    public static String publicBaseString(long partnerId, String path, long timestamp) {
        return partnerId + path + timestamp;
    }

    public static String shopBaseString(long partnerId, String path, long timestamp, String accessToken, long shopId) {
        return partnerId + path + timestamp + accessToken + shopId;
    }

    public static String merchantBaseString(long partnerId, String path, long timestamp, String accessToken, long merchantId) {
        return partnerId + path + timestamp + accessToken + merchantId;
    }

    public static String signPublic(String partnerKey, long partnerId, String path, long timestamp) {
        return sign(partnerKey, publicBaseString(partnerId, path, timestamp));
    }

    public static String signShop(String partnerKey, long partnerId, String path, long timestamp, String accessToken, long shopId) {
        return sign(partnerKey, shopBaseString(partnerId, path, timestamp, accessToken, shopId));
    }

    public static String signMerchant(String partnerKey, long partnerId, String path, long timestamp, String accessToken, long merchantId) {
        return sign(partnerKey, merchantBaseString(partnerId, path, timestamp, accessToken, merchantId));
    }

    public static String sign(String partnerKey, String baseString) {
        if (partnerKey == null || partnerKey.isBlank()) {
            throw new IllegalArgumentException("Shopee partner key cannot be empty");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(partnerKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
