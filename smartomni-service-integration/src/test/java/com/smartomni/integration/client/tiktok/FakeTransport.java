package com.smartomni.integration.client.tiktok;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Records requests and answers with a scripted function. */
public class FakeTransport implements TiktokHttpTransport {

    public record Sent(String method, URI uri, Map<String, String> headers, String body) {
    }

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Function<Sent, Response> handler;

    public FakeTransport(Function<Sent, Response> handler) {
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

    public static Response ok(String dataJson) {
        return new Response(200, "{\"code\":0,\"message\":\"Success\",\"request_id\":\"R1\",\"data\":" + dataJson + "}");
    }

    public static Response apiError(int code, String message) {
        return new Response(200, "{\"code\":" + code + ",\"message\":\"" + message + "\",\"request_id\":\"R2\"}");
    }
}
