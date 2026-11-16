package com.smartomni.integration.client.tiktok;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal HTTP seam so signing, decoding and pagination are testable without a network. */
public interface TiktokHttpTransport {

    Response send(String method, URI uri, Map<String, String> headers, String body);

    default BinaryResponse sendBinary(String method, URI uri, Map<String, String> headers, String body) {
        Response text = send(method, uri, headers, body);
        byte[] bytes = text.body() == null ? new byte[0] : text.body().getBytes(StandardCharsets.UTF_8);
        return new BinaryResponse(text.status(), "application/json", bytes);
    }

    record Response(int status, String body) {
    }

    record BinaryResponse(int status, String contentType, byte[] body) {
    }
}
