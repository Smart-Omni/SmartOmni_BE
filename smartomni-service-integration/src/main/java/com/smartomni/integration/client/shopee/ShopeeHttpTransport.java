package com.smartomni.integration.client.shopee;

import java.net.URI;
import java.util.Map;

/** Minimal HTTP seam so signing, decoding and token exchange are testable without a network. */
public interface ShopeeHttpTransport {

    Response send(String method, URI uri, Map<String, String> headers, String body);

    record Response(int status, String body) {
    }
}
