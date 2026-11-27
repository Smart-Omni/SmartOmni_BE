package com.smartomni.integration.client.shopee;

import com.smartomni.integration.config.ShopeeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClientShopeeHttpTransportTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void defaultPropertiesConfigure120SecondTransientRetry() {
        ShopeeProperties properties = new ShopeeProperties();
        assertEquals(1, properties.getTransientRetryMaxAttempts());
        assertEquals(Duration.ofSeconds(120), properties.getTransientRetryDelay());
    }

    @Test
    void retriesOnceAfterConfiguredDelayOnTimeoutAndSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v2/auth/access_token/get", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"error\":\"\",\"response\":{\"access_token\":\"AT-2\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        List<Duration> slept = new ArrayList<>();
        WebClientShopeeHttpTransport transport = new WebClientShopeeHttpTransport(
                Duration.ofMillis(400), 1, Duration.ofSeconds(120), slept::add);

        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/auth/access_token/get");
        ShopeeHttpTransport.Response response = transport.send("POST", uri, Map.of(), "{}");

        assertEquals(200, response.status());
        assertTrue(response.body().contains("AT-2"));
        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofSeconds(120)), slept);
    }

    @Test
    void retriesOnHttp503AndSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v2/shop/get_shop_info", exchange -> {
            int n = calls.incrementAndGet();
            int status = n == 1 ? 503 : 200;
            byte[] body = (n == 1 ? "Service Unavailable" : "{\"error\":\"\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        List<Duration> slept = new ArrayList<>();
        WebClientShopeeHttpTransport transport = new WebClientShopeeHttpTransport(
                Duration.ofSeconds(2), 1, Duration.ofSeconds(120), slept::add);

        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/shop/get_shop_info");
        ShopeeHttpTransport.Response response = transport.send("GET", uri, Map.of(), null);

        assertEquals(200, response.status());
        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofSeconds(120)), slept);
    }

    @Test
    void doesNotRetryOnClientError400() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v2/auth/access_token/get", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"error_param\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        List<Duration> slept = new ArrayList<>();
        WebClientShopeeHttpTransport transport = new WebClientShopeeHttpTransport(
                Duration.ofSeconds(2), 1, Duration.ofSeconds(120), slept::add);

        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/auth/access_token/get");
        ShopeeHttpTransport.Response response = transport.send("POST", uri, Map.of(), "{}");

        assertEquals(400, response.status());
        assertEquals(1, calls.get());
        assertTrue(slept.isEmpty());
    }

    @Test
    void throwsTransientExceptionWhenRetriesExhausted() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v2/auth/access_token/get", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        List<Duration> slept = new ArrayList<>();
        WebClientShopeeHttpTransport transport = new WebClientShopeeHttpTransport(
                Duration.ofMillis(400), 1, Duration.ofSeconds(120), slept::add);

        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/auth/access_token/get");
        ShopeeApiException ex = assertThrows(ShopeeApiException.class,
                () -> transport.send("POST", uri, Map.of(), "{}"));

        assertTrue(ex.isTransientFailure());
        assertEquals(2, calls.get());
        assertEquals(List.of(Duration.ofSeconds(120)), slept);
    }
}
