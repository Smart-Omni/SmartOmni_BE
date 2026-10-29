package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Shared decoding of the Shopee response envelope {@code {error, message, response, request_id}}
 * and URL building.
 */
public final class ShopeeEnvelope {

    private ShopeeEnvelope() {
    }

    /**
     * Decodes response body. Returns {@code response} node if error is empty, throws {@link ShopeeApiException} otherwise.
     */
    public static JsonNode decode(ObjectMapper mapper, ShopeeHttpTransport.Response response) {
        int status = response.status();
        JsonNode root = null;
        try {
            root = response.body() == null || response.body().isBlank() ? null : mapper.readTree(response.body());
        } catch (Exception ignored) {
            // Non-JSON body: handled below by status.
        }

        String error = root != null && root.path("error").isTextual() ? root.path("error").asText() : "";
        String requestId = root != null && root.path("request_id").isTextual() ? root.path("request_id").asText() : null;
        String message = root != null && root.path("message").isTextual() ? truncate(root.path("message").asText()) : "no message";

        if (status >= 500 || status == 429) {
            throw new ShopeeApiException("Shopee unavailable: HTTP " + status, status, error, requestId, true);
        }
        if (status < 200 || status >= 300) {
            throw new ShopeeApiException("Shopee rejected request: HTTP " + status + " " + message, status, error, requestId, false);
        }
        if (root == null) {
            throw new ShopeeApiException("Shopee response is not a valid JSON envelope", status, null, requestId, false);
        }
        if (!error.isEmpty()) {
            throw new ShopeeApiException("Shopee error " + error + ": " + message, status, error, requestId, false);
        }

        JsonNode res = root.path("response");
        return res.isMissingNode() || res.isNull() ? mapper.createObjectNode() : res;
    }

    public static URI buildUri(String base, String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(trimTrailingSlash(base)).append(path);
        char separator = '?';
        for (Map.Entry<String, String> entry : query.entrySet()) {
            url.append(separator).append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            separator = '&';
        }
        return URI.create(url.toString());
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
