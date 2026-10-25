package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Shared decoding of the TikTok response envelope {@code {code, message, request_id, data}} and URL building. */
final class TiktokEnvelope {

    private TiktokEnvelope() {
    }

    /** Returns {@code data} (an empty object node when absent) for code 0, otherwise throws. */
    static JsonNode decode(ObjectMapper mapper, TiktokHttpTransport.Response response) {
        int status = response.status();
        JsonNode root = null;
        try {
            root = response.body() == null || response.body().isBlank() ? null : mapper.readTree(response.body());
        } catch (Exception ignored) {
            // Non-JSON body: handled below by status.
        }
        Integer code = root != null && root.path("code").isInt() ? root.path("code").asInt() : null;
        String requestId = root != null && root.path("request_id").isTextual() ? root.path("request_id").asText() : null;
        // The platform's message is a short human sentence, never a credential; the URL is never included.
        String message = root != null && root.path("message").isTextual() ? truncate(root.path("message").asText()) : "no message";
        if (status >= 500 || status == 429) {
            throw new TiktokApiException("TikTok unavailable: HTTP " + status, status, code, requestId, true);
        }
        if (status < 200 || status >= 300) {
            throw new TiktokApiException("TikTok rejected the request: HTTP " + status + " " + message,
                    status, code, requestId, false);
        }
        if (root == null || code == null) {
            throw new TiktokApiException("TikTok response is not a valid envelope", status, null, requestId, false);
        }
        if (code != 0) {
            throw new TiktokApiException("TikTok error " + code + ": " + message, status, code, requestId, false);
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() || data.isNull() ? mapper.createObjectNode() : data;
    }

    static URI buildUri(String base, String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(trimTrailingSlash(base)).append(path);
        char separator = '?';
        for (Map.Entry<String, String> entry : query.entrySet()) {
            url.append(separator).append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            separator = '&';
        }
        return URI.create(url.toString());
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
