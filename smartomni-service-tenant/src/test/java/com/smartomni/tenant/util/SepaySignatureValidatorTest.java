package com.smartomni.tenant.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SepaySignatureValidator Unit Tests")
class SepaySignatureValidatorTest {

    private SepaySignatureValidator validator;
    private final String secret = "secret_key_123456";

    @BeforeEach
    void setUp() {
        validator = new SepaySignatureValidator();
    }

    @Test
    @DisplayName("validate: Chữ ký hợp lệ và timestamp trong ngưỡng 5 phút -> Thành công")
    void validate_validSignature_success() {
        String rawBody = "{\"id\":1001,\"transferAmount\":499000}";
        long timestamp = Instant.now().getEpochSecond();
        String payloadToSign = timestamp + "." + rawBody;
        String signature = validator.computeHmacSha256(payloadToSign, secret);

        boolean valid = validator.validate(rawBody, "sha256=" + signature, String.valueOf(timestamp), secret);
        assertTrue(valid);

        // Also test without sha256= prefix
        boolean validRaw = validator.validate(rawBody, signature, String.valueOf(timestamp), secret);
        assertTrue(validRaw);
    }

    @Test
    @DisplayName("validate: Payload bị chỉnh sửa sau khi ký -> Thất bại")
    void validate_tamperedBody_fails() {
        String originalBody = "{\"id\":1001,\"transferAmount\":499000}";
        long timestamp = Instant.now().getEpochSecond();
        String signature = validator.computeHmacSha256(timestamp + "." + originalBody, secret);

        String tamperedBody = "{\"id\":1001,\"transferAmount\":10000}";
        boolean valid = validator.validate(tamperedBody, signature, String.valueOf(timestamp), secret);
        assertFalse(valid);
    }

    @Test
    @DisplayName("validate: Secret key không khớp -> Thất bại")
    void validate_wrongSecret_fails() {
        String rawBody = "{\"id\":1001}";
        long timestamp = Instant.now().getEpochSecond();
        String signature = validator.computeHmacSha256(timestamp + "." + rawBody, "wrong_secret");

        boolean valid = validator.validate(rawBody, signature, String.valueOf(timestamp), secret);
        assertFalse(valid);
    }

    @Test
    @DisplayName("validate: Edge case - Timestamp quá cũ (>300 giây) để chống Replay Attack -> Thất bại")
    void validate_expiredTimestamp_fails() {
        String rawBody = "{\"id\":1001}";
        long oldTimestamp = Instant.now().getEpochSecond() - 301;
        String signature = validator.computeHmacSha256(oldTimestamp + "." + rawBody, secret);

        boolean valid = validator.validate(rawBody, signature, String.valueOf(oldTimestamp), secret);
        assertFalse(valid);
    }

    @Test
    @DisplayName("validate: Edge case - Headers hoặc Secret rỗng / null -> Thất bại")
    void validate_missingParams_fails() {
        long timestamp = Instant.now().getEpochSecond();
        assertFalse(validator.validate("{}", "sig", String.valueOf(timestamp), null));
        assertFalse(validator.validate("{}", "sig", String.valueOf(timestamp), ""));
        assertFalse(validator.validate("{}", null, String.valueOf(timestamp), secret));
        assertFalse(validator.validate("{}", "sig", null, secret));
        assertFalse(validator.validate("{}", "sig", "not_a_number", secret));
    }
}
