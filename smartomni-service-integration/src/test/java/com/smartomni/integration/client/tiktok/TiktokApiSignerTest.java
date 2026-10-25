package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the Java signer against the simulator contract's vectors (vendored copy in src/test/resources/tiktok-contract). */
class TiktokApiSignerTest {

    private static final Path VECTORS = Path.of("src", "test", "resources", "tiktok-contract", "test-vectors.json");

    @Test
    void matchesEveryApiVectorOfTheContract() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(VECTORS));
        JsonNode vectors = root.path("api");
        assertTrue(vectors.size() >= 5, "contract vectors not found/changed: " + VECTORS.toAbsolutePath());
        for (JsonNode vector : vectors) {
            Map<String, String> query = new LinkedHashMap<>();
            vector.path("query").fields().forEachRemaining(e -> query.put(e.getKey(), e.getValue().asText()));
            String body = vector.path("body").isNull() ? null : vector.path("body").asText();
            String secret = vector.path("app_secret").asText();
            String path = vector.path("path").asText();

            assertEquals(vector.path("string_to_sign").asText(), TiktokApiSigner.stringToSign(secret, path, query, body),
                    "string to sign: " + vector.path("name").asText());
            assertEquals(vector.path("sign").asText(), TiktokApiSigner.sign(secret, path, query, body),
                    "sign: " + vector.path("name").asText());
        }
    }

    @Test
    void anyChangeToPathQueryBodyOrSecretChangesTheSignature() {
        Map<String, String> query = Map.of("app_key", "k", "timestamp", "1700000000");
        String base = TiktokApiSigner.sign("s", "/a", query, "{}");
        assertNotEquals(base, TiktokApiSigner.sign("s2", "/a", query, "{}"));
        assertNotEquals(base, TiktokApiSigner.sign("s", "/b", query, "{}"));
        assertNotEquals(base, TiktokApiSigner.sign("s", "/a", query, "{ }"));
        assertNotEquals(base, TiktokApiSigner.sign("s", "/a", Map.of("app_key", "k", "timestamp", "1700000001"), "{}"));
    }

    @Test
    void signAndAccessTokenAreNotSigned() {
        Map<String, String> plain = Map.of("app_key", "k", "timestamp", "1");
        Map<String, String> withExcluded = Map.of("app_key", "k", "timestamp", "1", "sign", "x", "access_token", "y");
        assertEquals(TiktokApiSigner.sign("s", "/a", plain, null), TiktokApiSigner.sign("s", "/a", withExcluded, ""));
    }
}
