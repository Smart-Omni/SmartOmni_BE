package com.smartomni.integration.webhook.shopee;

import com.smartomni.integration.config.ShopeeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Validates HMAC-SHA256 signature and timestamp tolerance for Shopee Push Webhooks (FR-058).
 *
 * <p>Shopee Webhook Signature Specification:
 * <ul>
 *   <li>Header: {@code Authorization: <hex_digest>}</li>
 *   <li>Base string: {@code <webhook_url>|<raw_body>}</li>
 *   <li>Algorithm: {@code HMAC_SHA256(partner_key, base_string)}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopeeWebhookValidator {

    private static final String HMAC_SHA256 = "HmacSHA256";
    public static final long DEFAULT_TIMESTAMP_TOLERANCE_SECONDS = 300L; // 5 minutes

    private final ShopeeProperties properties;
    private final Clock clock;

    public ShopeeWebhookValidator(ShopeeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * Checks if the received signature matches the computed HMAC-SHA256 digest of
     * either the full URL + payload or the URI path + payload.
     *
     * @param receivedSignature Authorization or X-Signature header value
     * @param requestUrl        Full request URL (e.g. {@code http://localhost:8080/webhook/1/shopee})
     * @param requestUri        Request URI path (e.g. {@code /webhook/1/shopee})
     * @param rawPayload        Exact raw JSON payload body
     * @param secretKeyOverride Explicit partner key override, or null to use {@link ShopeeProperties#getPartnerKey()}
     * @return true if signature is valid, false otherwise
     */
    public boolean isValidSignature(String receivedSignature, String requestUrl, String requestUri,
                                    String rawPayload, String secretKeyOverride) {
        if (receivedSignature == null || receivedSignature.isBlank()) {
            log.warn("Shopee webhook rejected: missing signature header");
            return false;
        }

        String key = (secretKeyOverride != null && !secretKeyOverride.isBlank())
                ? secretKeyOverride
                : properties.getPartnerKey();

        if (key == null || key.isBlank()) {
            log.error("Shopee webhook validation failed: no partnerKey configured");
            return false;
        }

        String cleanSignature = cleanSignatureHeader(receivedSignature);
        String body = rawPayload != null ? rawPayload : "";

        // Check full URL base: url + "|" + body
        if (requestUrl != null && !requestUrl.isBlank()) {
            String urlBase = requestUrl + "|" + body;
            String computedUrlSig = computeHmacSha256(urlBase, key);
            if (computedUrlSig.equalsIgnoreCase(cleanSignature)) {
                return true;
            }
        }

        // Check path base: uri + "|" + body
        if (requestUri != null && !requestUri.isBlank()) {
            String pathBase = requestUri + "|" + body;
            String computedPathSig = computeHmacSha256(pathBase, key);
            if (computedPathSig.equalsIgnoreCase(cleanSignature)) {
                return true;
            }
        }

        // Also check raw body alone as fallback
        String bodySig = computeHmacSha256(body, key);
        if (bodySig.equalsIgnoreCase(cleanSignature)) {
            return true;
        }

        log.warn("Shopee webhook rejected: signature mismatch for URL={}, URI={}", requestUrl, requestUri);
        return false;
    }

    /**
     * Checks if the webhook timestamp is within allowable skew to guard against replay attacks.
     *
     * @param timestampSeconds Epoch timestamp from webhook payload (seconds or milliseconds)
     * @param toleranceSeconds Max allowed difference in seconds
     * @return true if timestamp is valid, false if expired/replay
     */
    public boolean isTimestampValid(Long timestampSeconds, long toleranceSeconds) {
        if (timestampSeconds == null || timestampSeconds <= 0) {
            return true; // Not provided -> skip timestamp check
        }
        long sec = timestampSeconds;
        if (sec > 100_000_000_000L) {
            sec = sec / 1000;
        }
        long currentSec = clock.instant().getEpochSecond();
        long diff = Math.abs(currentSec - sec);
        if (diff > toleranceSeconds) {
            log.warn("Shopee webhook rejected: timestamp skew {}s exceeds tolerance {}s (current={}, payload={})",
                    diff, toleranceSeconds, currentSec, sec);
            return false;
        }
        return true;
    }

    public boolean isTimestampValid(Long timestampSeconds) {
        return isTimestampValid(timestampSeconds, DEFAULT_TIMESTAMP_TOLERANCE_SECONDS);
    }

    public String computeHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Failed to compute HMAC-SHA256", e);
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    private String cleanSignatureHeader(String header) {
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "SHA256=", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }
}
