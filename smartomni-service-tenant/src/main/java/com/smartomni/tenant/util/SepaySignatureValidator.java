package com.smartomni.tenant.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

@Slf4j
@Component
public class SepaySignatureValidator {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long MAX_ALLOWED_DRIFT_SECONDS = 300L; // 5 minutes

    public boolean validate(String rawBody, String signatureHeader, String timestampHeader, String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("SePay webhook verification rejected: webhook secret is empty");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("SePay webhook verification rejected: X-SePay-Signature is missing");
            return false;
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            log.warn("SePay webhook verification rejected: X-SePay-Timestamp is missing");
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            log.warn("SePay webhook verification rejected: Invalid timestamp format '{}'", timestampHeader);
            return false;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > MAX_ALLOWED_DRIFT_SECONDS) {
            log.warn("SePay webhook verification rejected: Timestamp drift exceeded {}s (server: {}, received: {})",
                    MAX_ALLOWED_DRIFT_SECONDS, now, timestamp);
            return false;
        }

        String actualSignature = signatureHeader.trim();
        if (actualSignature.startsWith("sha256=")) {
            actualSignature = actualSignature.substring(7);
        }

        String payloadToSign = timestampHeader.trim() + "." + (rawBody != null ? rawBody : "");
        String expectedSignature = computeHmacSha256(payloadToSign, secret);

        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                actualSignature.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }

    public String computeHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
