package com.smartomni.integration.client.shopee;

import com.smartomni.integration.config.ShopeeProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Component
public class WebClientShopeeHttpTransport implements ShopeeHttpTransport {

    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    private final Duration timeout;

    public WebClientShopeeHttpTransport(ShopeeProperties properties) {
        this.timeout = properties.getRequestTimeout();
    }

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body) {
        try {
            WebClient.RequestBodySpec spec = webClient.method(HttpMethod.valueOf(method)).uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::set));
            WebClient.RequestHeadersSpec<?> request = body == null || body.isEmpty() ? spec : spec.bodyValue(body);
            return request.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                            .map(text -> new Response(response.statusCode().value(), text)))
                    .block(timeout);
        } catch (RuntimeException e) {
            // Never embed request URI with secrets in the exception message
            throw ShopeeApiException.transport("Shopee request failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
