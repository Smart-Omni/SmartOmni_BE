package com.smartomni.integration.webhook.shopee;

import com.smartomni.integration.config.ShopeeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ShopeeWebhookValidator Unit Tests")
class ShopeeWebhookValidatorTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final String PARTNER_KEY = "shopee_mock_secret_key_2026";

    private ShopeeProperties properties;
    private ShopeeWebhookValidator validator;

    @BeforeEach
    void setUp() {
        properties = new ShopeeProperties();
        properties.setPartnerId(1001141L);
        properties.setPartnerKey(PARTNER_KEY);

        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        validator = new ShopeeWebhookValidator(properties, fixedClock);
    }

    @Test
    @DisplayName("Validates signature successfully with full URL + raw body")
    void isValidSignature_WithFullUrl_Succeeds() {
        String url = "http://localhost:8080/webhook/1/shopee";
        String uri = "/webhook/1/shopee";
        String payload = "{\"code\":3,\"shop_id\":727720655,\"timestamp\":1793095200,\"data\":{\"ordersn\":\"241027SP01\"}}";

        String expectedSig = validator.computeHmacSha256(url + "|" + payload, PARTNER_KEY);

        boolean valid = validator.isValidSignature(expectedSig, url, uri, payload, null);
        assertTrue(valid);
    }

    @Test
    @DisplayName("Validates signature successfully with URI path + raw body")
    void isValidSignature_WithUriPath_Succeeds() {
        String url = "http://proxy.internal:8080/webhook/1/shopee";
        String uri = "/webhook/1/shopee";
        String payload = "{\"code\":3,\"shop_id\":727720655,\"timestamp\":1793095200,\"data\":{\"ordersn\":\"241027SP01\"}}";

        String expectedSig = validator.computeHmacSha256(uri + "|" + payload, PARTNER_KEY);

        boolean valid = validator.isValidSignature(expectedSig, url, uri, payload, null);
        assertTrue(valid);
    }

    @Test
    @DisplayName("Validates signature with Bearer or SHA256= prefix")
    void isValidSignature_WithPrefix_Succeeds() {
        String url = "http://localhost:8080/webhook/1/shopee";
        String payload = "{\"code\":3}";
        String rawSig = validator.computeHmacSha256(url + "|" + payload, PARTNER_KEY);

        assertTrue(validator.isValidSignature("Bearer " + rawSig, url, "/webhook/1/shopee", payload, null));
        assertTrue(validator.isValidSignature("SHA256=" + rawSig, url, "/webhook/1/shopee", payload, null));
    }

    @Test
    @DisplayName("Rejects signature when raw payload is tampered")
    void isValidSignature_TamperedPayload_Fails() {
        String url = "http://localhost:8080/webhook/1/shopee";
        String payload = "{\"code\":3}";
        String validSig = validator.computeHmacSha256(url + "|" + payload, PARTNER_KEY);

        assertFalse(validator.isValidSignature(validSig, url, "/webhook/1/shopee", payload + "tampered", null));
    }

    @Test
    @DisplayName("Rejects signature when signature header is missing or empty")
    void isValidSignature_EmptyHeader_Fails() {
        assertFalse(validator.isValidSignature(null, "http://url", "/uri", "{}", null));
        assertFalse(validator.isValidSignature("   ", "http://url", "/uri", "{}", null));
    }

    @Test
    @DisplayName("Validates timestamp within 300s window")
    void isTimestampValid_WithinTolerance_Succeeds() {
        long currentSec = FIXED_NOW.getEpochSecond();

        assertTrue(validator.isTimestampValid(currentSec));
        assertTrue(validator.isTimestampValid(currentSec - 200L));
        assertTrue(validator.isTimestampValid(currentSec + 200L));
    }

    @Test
    @DisplayName("Validates timestamp in milliseconds")
    void isTimestampValid_Milliseconds_Succeeds() {
        long currentMillis = FIXED_NOW.toEpochMilli();

        assertTrue(validator.isTimestampValid(currentMillis));
    }

    @Test
    @DisplayName("Rejects timestamp skewed by more than 300s")
    void isTimestampValid_ExpiredSkew_Fails() {
        long currentSec = FIXED_NOW.getEpochSecond();

        assertFalse(validator.isTimestampValid(currentSec - 301L));
        assertFalse(validator.isTimestampValid(currentSec + 301L));
    }

    @Test
    @DisplayName("Null or non-positive timestamp is accepted")
    void isTimestampValid_NullOrZero_Succeeds() {
        assertTrue(validator.isTimestampValid(null));
        assertTrue(validator.isTimestampValid(0L));
    }
}
