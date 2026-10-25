package com.smartomni.integration.client.tiktok;

import com.smartomni.integration.config.TiktokProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Component
public class WebClientTiktokHttpTransport implements TiktokHttpTransport {

    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    private final Duration timeout;

    public WebClientTiktokHttpTransport(TiktokProperties properties) {
        this.timeout = properties.getRequestTimeout();
    }

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body) {
        try {
            WebClient.RequestBodySpec spec = webClient.method(HttpMethod.valueOf(method)).uri(uri)
                    .headers(h -> headers.forEach(h::set));
            WebClient.RequestHeadersSpec<?> request = body == null || body.isEmpty() ? spec : spec.bodyValue(body);
            return request.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                            .map(text -> new Response(response.statusCode().value(), text)))
                    .block(timeout);
        } catch (RuntimeException e) {
            // WebClient exception messages embed the request URI; app_secret and auth_code travel in it.
            throw TiktokApiException.transport("TikTok request failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
