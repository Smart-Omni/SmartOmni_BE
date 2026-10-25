package com.smartomni.integration.service.tiktok;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenCipherTest {

    private static final String KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Test
    void roundTripsWithRandomIvAndNeverStoresPlaintext() {
        TokenCipher cipher = new TokenCipher(KEY);
        String a = cipher.encrypt("access-token-value");
        String b = cipher.encrypt("access-token-value");
        assertNotEquals(a, b, "random IV: same plaintext gives different ciphertext");
        assertFalse(a.contains("access-token-value"));
        assertEquals("access-token-value", cipher.decrypt(a));
    }

    @Test
    void tamperedOrForeignCiphertextIsRejected() {
        TokenCipher cipher = new TokenCipher(KEY);
        String stored = cipher.encrypt("secret");
        String tampered = stored.substring(0, stored.length() - 4) + (stored.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("plain"));
        assertThrows(IllegalStateException.class, () -> new TokenCipher("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=").decrypt(stored));
    }
}
