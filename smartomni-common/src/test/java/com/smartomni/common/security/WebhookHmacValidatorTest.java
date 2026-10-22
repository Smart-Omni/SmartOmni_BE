package com.smartomni.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WebhookHmacValidator Unit Tests")
class WebhookHmacValidatorTest {

    private WebhookHmacValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WebhookHmacValidator();
    }

    @Test
    @DisplayName("Shopee: Xác thực thành công chữ ký số HMAC-SHA256 chuẩn (path + body)")
    void testValidateShopeeWebhook_Success() {
        String path = "/webhook/100/shopee";
        String rawBody = "{\"data\":{\"order_id\":\"SP123456\"}}";
        String partnerKey = "shopee-partner-secret-key-123456";

        // Tính chữ ký kỳ vọng
        String contentToSign = path + "|" + rawBody;
        String expectedSignature = validator.calculateHmacSha256(contentToSign, partnerKey);

        boolean isValid = validator.validateShopeeWebhook(path, rawBody, expectedSignature, partnerKey);
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Shopee: Từ chối khi chữ ký sai hoặc payload bị can thiệp")
    void testValidateShopeeWebhook_TamperedPayload_Fails() {
        String path = "/webhook/100/shopee";
        String rawBody = "{\"data\":{\"order_id\":\"SP123456\"}}";
        String partnerKey = "shopee-partner-secret-key-123456";

        boolean isValid = validator.validateShopeeWebhook(path, rawBody + "tampered", "invalid-signature", partnerKey);
        assertFalse(isValid);
    }

    @Test
    @DisplayName("TikTok Shop: Xác thực thành công khi chữ ký đúng và timestamp hợp lệ")
    void testValidateTikTokWebhook_Success() {
        String appSecret = "tiktok-app-secret-key-999";
        String path = "/webhook/100/tiktok";
        long timestamp = Instant.now().getEpochSecond();
        String rawBody = "{\"type\":1,\"order_id\":\"TT987654\"}";

        String contentToSign = appSecret + path + timestamp + rawBody;
        String expectedSignature = validator.calculateHmacSha256(contentToSign, appSecret);

        boolean isValid = validator.validateTikTokWebhook(appSecret, path, timestamp, rawBody, expectedSignature);
        assertTrue(isValid);
    }

    @Test
    @DisplayName("TikTok Shop: Từ chối request khi độ lệch thời gian > 5 phút (300 giây) để chống Replay Attack")
    void testValidateTikTokWebhook_TimestampSkewOver5Minutes_Fails() {
        String appSecret = "tiktok-app-secret-key-999";
        String path = "/webhook/100/tiktok";
        // Giả lập timestamp cách đây 10 phút (600 giây)
        long oldTimestamp = Instant.now().getEpochSecond() - 600;
        String rawBody = "{\"type\":1,\"order_id\":\"TT987654\"}";

        String contentToSign = appSecret + path + oldTimestamp + rawBody;
        String expectedSignature = validator.calculateHmacSha256(contentToSign, appSecret);

        boolean isValid = validator.validateTikTokWebhook(appSecret, path, oldTimestamp, rawBody, expectedSignature);
        assertFalse(isValid, "Phải từ chối webhook có timestamp lệch quá 5 phút");
    }
}
