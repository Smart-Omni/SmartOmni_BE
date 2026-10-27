package com.smartomni.integration.client.shopee;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Records requests and responds with a scripted function for offline testing. */
public class FakeShopeeTransport implements ShopeeHttpTransport {

    public record Sent(String method, URI uri, Map<String, String> headers, String body) {
    }

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Function<Sent, Response> handler;

    public FakeShopeeTransport(Function<Sent, Response> handler) {
        this.handler = handler;
    }

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body) {
        Sent request = new Sent(method, uri, Map.copyOf(headers), body);
        sent.add(request);
        return handler.apply(request);
    }

    public List<Sent> sentTo(String pathFragment) {
        List<Sent> out = new ArrayList<>();
        for (Sent s : sent) {
            if (s.uri().getPath().contains(pathFragment)) {
                out.add(s);
            }
        }
        return out;
    }

    public static Response ok(String responseJson) {
        return new Response(200, "{\"error\":\"\",\"message\":\"\",\"request_id\":\"REQ-OK\",\"response\":" + responseJson + "}");
    }

    public static Response shopeeError(String error, String message) {
        return new Response(200, "{\"error\":\"" + error + "\",\"message\":\"" + message + "\",\"request_id\":\"REQ-ERR\"}");
    }
}
