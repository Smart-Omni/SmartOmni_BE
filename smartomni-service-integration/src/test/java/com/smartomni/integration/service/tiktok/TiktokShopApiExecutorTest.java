package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.FakeTransport;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.client.tiktok.TiktokHttpTransport;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.TokenSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TiktokShopApiExecutorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private final TokenCipher cipher = new TokenCipher("QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private TiktokProperties properties;
    private InMemoryTokenStore store;
    private final AtomicInteger apiCalls = new AtomicInteger();
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private final java.util.List<String> tokensSeen = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<String> ciphersSeen = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new TiktokProperties();
        properties.setAppKey("k");
        properties.setAppSecret("s");
        store = new InMemoryTokenStore(new TokenSnapshot(10L, 1L, ConnectionStatus.CONNECTED, "CIPHER-A", 1,
                cipher.encrypt("access-1"), cipher.encrypt("refresh-1"), NOW.plusSeconds(3600), NOW.plusSeconds(86400)));
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TiktokShopApiExecutor executor(int rejectUntilCall) {
        TiktokHttpTransport transport = (method, uri, headers, body) -> {
            if (uri.getPath().startsWith("/api/v2/token")) {
                refreshCalls.incrementAndGet();
                return FakeTransport.ok("{\"access_token\":\"access-2\",\"access_token_expire_in\":" + NOW.plusSeconds(86400).getEpochSecond()
                        + ",\"refresh_token\":\"refresh-2\",\"refresh_token_expire_in\":" + NOW.plusSeconds(86400L * 30).getEpochSecond() + "}");
            }
            int call = apiCalls.incrementAndGet();
            tokensSeen.add(headers.get("x-tts-access-token"));
            ciphersSeen.add(URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8));
            return call <= rejectUntilCall ? new TiktokHttpTransport.Response(401, "{\"code\":-1,\"message\":\"token_expired\"}")
                    : FakeTransport.ok("{\"ok\":true}");
        };
        ObjectMapper mapper = new ObjectMapper();
        TiktokTokenService tokens = new TiktokTokenService(store, new TiktokAuthClient(properties, transport, mapper),
                cipher, (t, c, r) -> { }, properties, clock);
        return new TiktokShopApiExecutor(tokens, new TiktokOpenApiClient(properties, transport, clock, mapper), properties);
    }

    @Test
    void usesTheConnectionsOwnTokenAndShopCipher() {
        assertTrue(executor(0).call(10L, "GET", "/order/202507/orders", Map.of("ids", "1"), null).path("ok").asBoolean());
        assertEquals("access-1", tokensSeen.get(0));
        assertTrue(ciphersSeen.get(0).contains("shop_cipher=CIPHER-A"));
        assertEquals(0, refreshCalls.get());
    }

    @Test
    void anotherShopsConnectionIsNotReachable() {
        TenantContext.setTenantId(2L);
        assertEquals("TIKTOK_CONNECTION_NOT_FOUND", assertThrows(BusinessException.class,
                () -> executor(0).call(10L, "GET", "/order/202507/orders", Map.of(), null)).getErrorCode());
        assertEquals(0, apiCalls.get());
    }

    @Test
    void aRejectedTokenIsRefreshedOnceAndTheCallRetriedOnce() {
        assertTrue(executor(1).call(10L, "GET", "/order/202507/orders", Map.of(), null).path("ok").asBoolean());
        assertEquals(1, refreshCalls.get());
        assertEquals(2, apiCalls.get());
        assertEquals("access-2", tokensSeen.get(1));
    }

    @Test
    void aSecondRejectionIsNotRetriedAgain() {
        assertThrows(TiktokApiException.class, () -> executor(5).call(10L, "GET", "/order/202507/orders", Map.of(), null));
        assertEquals(2, apiCalls.get(), "one call plus one retry, never more");
        assertEquals(1, refreshCalls.get());
    }

    @Test
    void otherClientErrorsDoNotTriggerARefresh() {
        properties.setTokenRejectedHttpStatuses(java.util.List.of(401));
        TiktokHttpTransport forbidden = (method, uri, headers, body) -> new TiktokHttpTransport.Response(403, "{\"code\":-1,\"message\":\"missing scope\"}");
        ObjectMapper mapper = new ObjectMapper();
        TiktokTokenService tokens = new TiktokTokenService(store, new TiktokAuthClient(properties, forbidden, mapper),
                cipher, (t, c, r) -> { }, properties, clock);
        TiktokShopApiExecutor executor = new TiktokShopApiExecutor(tokens, new TiktokOpenApiClient(properties, forbidden, clock, mapper), properties);
        assertThrows(TiktokApiException.class, () -> executor.call(10L, "GET", "/x", Map.of(), null));
        assertEquals(1, store.snapshot().version());
    }
}
