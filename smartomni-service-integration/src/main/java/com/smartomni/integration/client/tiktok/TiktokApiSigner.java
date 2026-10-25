package com.smartomni.integration.client.tiktok;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * TikTok OpenAPI request signing, per {@code contracts/signing/sign.ts} (UNVERIFIED: the algorithm
 * mirrors the TTS Open Toolkit broker, not the Partner Center "common parameters" page).
 *
 * <pre>
 * StringToSign = secret + path + (name + value for each query param sorted by name, excluding "sign" and
 *                "access_token") + raw request body ("" if none) + secret
 * sign = lowercase_hex(HMAC_SHA256(key = secret, message = StringToSign))
 * </pre>
 * Query values are the decoded strings; the body is the exact string sent on the wire.
 * Multipart bodies are not supported (unverified), so only String bodies exist here.
 */
public final class TiktokApiSigner {

    private static final Set<String> EXCLUDED = Set.of("sign", "access_token");

    private TiktokApiSigner() {
    }

    public static String stringToSign(String secret, String path, Map<String, String> query, String body) {
        StringBuilder out = new StringBuilder(secret).append(path);
        // String.compareTo sorts by UTF-16 code unit, like sign.ts.
        for (Map.Entry<String, String> entry : new TreeMap<>(query).entrySet()) {
            if (!EXCLUDED.contains(entry.getKey())) {
                out.append(entry.getKey()).append(entry.getValue());
            }
        }
        return out.append(body == null ? "" : body).append(secret).toString();
    }

    public static String sign(String secret, String path, Map<String, String> query, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(stringToSign(secret, path, query, body).getBytes(StandardCharsets.UTF_8));
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
