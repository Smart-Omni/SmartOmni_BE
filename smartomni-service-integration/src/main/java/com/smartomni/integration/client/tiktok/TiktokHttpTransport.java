package com.smartomni.integration.client.tiktok;

import java.net.URI;
import java.util.Map;

/** Minimal HTTP seam so signing, decoding and pagination are testable without a network. */
public interface TiktokHttpTransport {

    Response send(String method, URI uri, Map<String, String> headers, String body);

    record Response(int status, String body) {
    }
}
