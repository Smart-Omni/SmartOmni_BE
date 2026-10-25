package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.TiktokProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TiktokOpenApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TiktokProperties properties;
    private Clock signingClock;

    @BeforeEach
    void setUp() {
        properties = new TiktokProperties();
        properties.setAppKey("test_app_key_not_real");
        properties.setAppSecret("test_app_secret_not_real");
        properties.setOpenApiBaseUrl("http://simulator.test:8090/");
        signingClock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    }

    private TiktokOpenApiClient client(FakeTransport transport) {
        return new TiktokOpenApiClient(properties, transport, signingClock, MAPPER);
    }

    private static Map<String, String> query(java.net.URI uri) {
        Map<String, String> out = new TreeMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void sendsSignedRequestWithTokenInHeaderAndSignsExactlyTheBodySent() {
        FakeTransport transport = new FakeTransport(s -> FakeTransport.ok("{\"products\":[]}"));
        String body = "{\"note\":\"Cửa hàng\",\"status\":\"ACTIVATE\"}";
        client(transport).call("POST", "/product/202502/products/search", Map.of("page_size", "10"), body, "ACCESS-1", "CIPHER-1");

        FakeTransport.Sent sent = transport.sent.get(0);
        assertEquals("POST", sent.method());
        assertEquals("simulator.test", sent.uri().getHost());
        assertEquals("/product/202502/products/search", sent.uri().getPath());
        assertEquals(body, sent.body(), "the exact string that was signed is the one sent");
        assertEquals("ACCESS-1", sent.headers().get("x-tts-access-token"));
        Map<String, String> q = query(sent.uri());
        assertFalse(q.containsKey("access_token"), "access token is a header, not a query parameter");
        assertEquals("1700000000", q.get("timestamp"));
        assertEquals("CIPHER-1", q.get("shop_cipher"));
        assertEquals("test_app_key_not_real", q.get("app_key"));
        assertEquals(TiktokApiSigner.sign("test_app_secret_not_real", "/product/202502/products/search", q, body), q.get("sign"));
        assertFalse(sent.uri().toString().contains("test_app_secret_not_real"), "secret is never sent");
    }

    @Test
    void signingTimestampComesFromTheSigningClock() {
        FakeTransport transport = new FakeTransport(s -> FakeTransport.ok("{}"));
        TiktokOpenApiClient c = client(transport);
        c.call("GET", "/x", Map.of(), null, "t", null);
        signingClock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        client(transport).call("GET", "/x", Map.of(), null, "t", null);
        assertEquals("1700000000", query(transport.sent.get(0).uri()).get("timestamp"));
        assertEquals("1800000000", query(transport.sent.get(1).uri()).get("timestamp"));
        assertFalse(query(transport.sent.get(0).uri()).containsKey("shop_cipher"));
    }

    @Test
    void authorizedShopsAreDecodedFromTheOasShape() {
        FakeTransport transport = new FakeTransport(s -> FakeTransport.ok(
                "{\"shops\":[{\"cipher\":\"C\",\"code\":\"K\",\"id\":\"7000000000000000001\",\"name\":\"N\",\"region\":\"VN\",\"seller_type\":\"LOCAL\"}]}"));
        List<TiktokOpenApiClient.AuthorizedShop> shops = client(transport).getAuthorizedShops("tok");
        assertEquals(1, shops.size());
        assertEquals("7000000000000000001", shops.get(0).id());
        assertEquals("C", shops.get(0).cipher());
        assertEquals("/authorization/202309/shops", transport.sent.get(0).uri().getPath());
        assertFalse(query(transport.sent.get(0).uri()).containsKey("shop_cipher"));
    }

    @Test
    void envelopeErrorsAreDecodedAsRejectionsAndServerErrorsAsTransient() {
        TiktokApiException rejected = assertThrows(TiktokApiException.class, () ->
                client(new FakeTransport(s -> FakeTransport.apiError(105004, "bad thing"))).call("GET", "/x", Map.of(), null, "t", null));
        assertTrue(rejected.isRejection());
        assertEquals(105004, rejected.getApiCode());
        assertEquals("R2", rejected.getRequestId());

        TiktokApiException http4xx = assertThrows(TiktokApiException.class, () ->
                client(new FakeTransport(s -> new TiktokHttpTransport.Response(401, "{\"code\":1,\"message\":\"nope\"}")))
                        .call("GET", "/x", Map.of(), null, "t", null));
        assertTrue(http4xx.isRejection());

        TiktokApiException http5xx = assertThrows(TiktokApiException.class, () ->
                client(new FakeTransport(s -> new TiktokHttpTransport.Response(503, "<html>"))).call("GET", "/x", Map.of(), null, "t", null));
        assertTrue(http5xx.isTransientFailure());

        assertThrows(TiktokApiException.class, () ->
                client(new FakeTransport(s -> new TiktokHttpTransport.Response(200, "not json"))).call("GET", "/x", Map.of(), null, "t", null));
    }

    @Test
    void missingCredentialsFailAtCallTimeNotAtConstruction() {
        properties.setAppSecret("");
        BusinessException e = assertThrows(BusinessException.class, () ->
                client(new FakeTransport(s -> FakeTransport.ok("{}"))).call("GET", "/x", Map.of(), null, "t", null));
        assertEquals("TIKTOK_NOT_CONFIGURED", e.getErrorCode());
    }

    @Test
    void paginationFollowsNextPageTokenUntilEmpty() {
        FakeTransport transport = new FakeTransport(s -> {
            String token = query(s.uri()).getOrDefault("page_token", "");
            return switch (token) {
                case "" -> FakeTransport.ok("{\"orders\":[1],\"next_page_token\":\"p2\"}");
                case "p2" -> FakeTransport.ok("{\"orders\":[2],\"next_page_token\":\"p3\"}");
                default -> FakeTransport.ok("{\"orders\":[3],\"next_page_token\":\"\"}");
            };
        });
        TiktokOpenApiClient c = client(transport);
        List<JsonNode> pages = c.fetchAllPages(token -> c.call("GET", "/order/202507/orders",
                token.isEmpty() ? Map.of() : Map.of("page_token", token), null, "t", "C"));
        assertEquals(3, pages.size());
        assertEquals(3, transport.sent.size());
    }

    @Test
    void paginationStopsOnRepeatedTokenAndOnMaxPages() {
        TiktokOpenApiClient c = client(new FakeTransport(s -> FakeTransport.ok("{}")));
        assertThrows(TiktokApiException.class, () -> c.fetchAllPages(t -> MAPPER.createObjectNode().put("next_page_token", "same")));

        properties.setMaxPages(5);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(TiktokApiException.class, () -> c.fetchAllPages(t ->
                MAPPER.createObjectNode().put("next_page_token", "p" + calls.incrementAndGet())));
        assertEquals(5, calls.get());
    }
}
