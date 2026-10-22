package com.smartomni.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Tiện ích kiểm tra chữ ký số HMAC-SHA256 chuẩn cho Webhook từ các sàn TMĐT (Shopee, TikTok Shop).
 * Hỗ trợ kiểm tra Timestamp Tolerance (mặc định 5 phút = 300 giây) để chống Replay Attack.
 */
@Slf4j
@Component
public class WebhookHmacValidator {

    public static final String HMAC_SHA256 = "HmacSHA256";
    public static final long DEFAULT_TIMESTAMP_TOLERANCE_SECONDS = 300; // 5 phút

    /**
     * Kiểm tra độ lệch thời gian của Webhook để chống Replay Attack.
     *
     * @param timestampSeconds Thời điểm gửi webhook (epoch seconds hoặc milliseconds)
     * @param toleranceSeconds Độ lệch tối đa cho phép (mặc định 300s = 5 phút)
     * @return true nếu timestamp nằm trong khoảng cho phép
     */
    public boolean isTimestampValid(long timestampSeconds, long toleranceSeconds) {
        long currentSeconds = Instant.now().getEpochSecond();
        // Nếu timestamp truyền vào là milliseconds (ví dụ > 100000000000L), đổi về seconds
        if (timestampSeconds > 100_000_000_000L) {
            timestampSeconds = timestampSeconds / 1000;
        }
        long diff = Math.abs(currentSeconds - timestampSeconds);
        return diff <= toleranceSeconds;
    }

    public boolean isTimestampValid(long timestampSeconds) {
        return isTimestampValid(timestampSeconds, DEFAULT_TIMESTAMP_TOLERANCE_SECONDS);
    }

    /**
     * Xác thực Webhook của Shopee.
     * Format chuẩn Shopee:
     * baseString = path + "|" + rawBody (hoặc url + "|" + rawBody)
     * signature = HMAC_SHA256(baseString, partnerKey)
     */
    public boolean validateShopeeWebhook(String path, String rawBody, String receivedSignature, String partnerKey) {
        if (receivedSignature == null || partnerKey == null) {
            return false;
        }
        String contentToSign = (path != null ? path : "") + "|" + (rawBody != null ? rawBody : "");
        String computedSignature = calculateHmacSha256(contentToSign, partnerKey);
        return computedSignature.equalsIgnoreCase(receivedSignature.trim());
    }

    /**
     * Xác thực Webhook của TikTok Shop.
     * Format chuẩn TikTok Shop:
     * baseString = appSecret + path + timestamp + rawBody
     * signature = HMAC_SHA256(baseString, appSecret)
     */
    public boolean validateTikTokWebhook(String appSecret, String path, long timestamp, String rawBody, String receivedSignature) {
        if (receivedSignature == null || appSecret == null) {
            return false;
        }
        // Kiểm tra timestamp tolerance (<= 5 phút)
        if (!isTimestampValid(timestamp)) {
            log.warn("TikTok webhook bị từ chối do lệch timestamp quá 5 phút: timestamp={}, hiện tại={}",
                    timestamp, Instant.now().getEpochSecond());
            return false;
        }

        String contentToSign = appSecret + (path != null ? path : "") + timestamp + (rawBody != null ? rawBody : "");
        String computedSignature = calculateHmacSha256(contentToSign, appSecret);
        return computedSignature.equalsIgnoreCase(receivedSignature.trim());
    }

    /**
     * Tiện ích tính toán HMAC-SHA256 trả về chuỗi Hexadecimal.
     */
    public String calculateHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Failed to calculate HMAC-SHA256", e);
            throw new RuntimeException("Error calculating HMAC-SHA256", e);
        }
    }
}
