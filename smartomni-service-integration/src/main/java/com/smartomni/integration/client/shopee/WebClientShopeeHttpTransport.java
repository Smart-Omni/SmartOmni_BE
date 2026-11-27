package com.smartomni.integration.client.shopee;

import com.smartomni.integration.config.ShopeeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
public class WebClientShopeeHttpTransport implements ShopeeHttpTransport {

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Sleeper sleeper;

    @Autowired
    public WebClientShopeeHttpTransport(ShopeeProperties properties) {
        this(properties.getRequestTimeout(),
                Math.max(0, properties.getTransientRetryMaxAttempts()),
                properties.getTransientRetryDelay(),
                duration -> Thread.sleep(duration.toMillis()));
    }

    WebClientShopeeHttpTransport(Duration timeout, int maxRetries, Duration retryDelay, Sleeper sleeper) {
        this.timeout = timeout;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryDelay = retryDelay != null ? retryDelay : Duration.ZERO;
        this.sleeper = sleeper;
    }

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body) {
        int attempt = 0;
        while (true) {
            try {
                Response response = sendOnce(method, uri, headers, body);
                if (isRetryableStatus(response.status()) && attempt < maxRetries) {
                    attempt++;
                    waitBeforeRetry(method, uri.getPath(), "HTTP " + response.status(), attempt);
                    continue;
                }
                return response;
            } catch (ShopeeApiException e) {
                if (!e.isTransientFailure() || attempt >= maxRetries) {
                    throw e;
                }
                attempt++;
                waitBeforeRetry(method, uri.getPath(), e.getMessage(), attempt);
            }
        }
    }

    private Response sendOnce(String method, URI uri, Map<String, String> headers, String body) {
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

    private static boolean isRetryableStatus(int status) {
        return status >= 500 || status == 429;
    }

    private void waitBeforeRetry(String method, String path, String reason, int attempt) {
        log.warn("Transient Shopee transport failure on {} {} ({}); retrying attempt {}/{} after {}s",
                method, path, reason, attempt, maxRetries, retryDelay.toSeconds());
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(retryDelay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ShopeeApiException.transport("Shopee retry interrupted");
        }
    }
}
