package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.TiktokProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TiktokAuthClientTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private TiktokProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TiktokProperties();
        properties.setAppKey("app-key-1");
        properties.setAppSecret("app-secret-1");
        properties.setTokenBaseUrl("http://auth.simulator.test:8090");
    }

    private TiktokAuthClient client(FakeTransport transport) {
        return new TiktokAuthClient(properties, transport, new ObjectMapper());
    }

    @Test
    void exchangeSendsConfiguredCredentialsToTheTokenHostAndDecodesTheGrant() {
        FakeTransport transport = new FakeTransport(s -> FakeTransport.ok(
                "{\"access_token\":\"AT\",\"access_token_expire_in\":1700086400,\"refresh_token\":\"RT\","
                        + "\"refresh_token_expire_in\":1707776000,\"open_id\":\"OID\",\"granted_scopes\":[\"a\",\"b\"]}"));
        TiktokAuthClient.TokenGrant grant = client(transport).exchangeAuthorizationCode("CODE 1", NOW);

        FakeTransport.Sent sent = transport.sent.get(0);
        assertEquals("GET", sent.method());
        assertEquals("auth.simulator.test", sent.uri().getHost());
        assertEquals("/api/v2/token/get", sent.uri().getPath());
        String query = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("app_key=app-key-1") && query.contains("app_secret=app-secret-1"));
        assertTrue(query.contains("auth_code=CODE 1") && query.contains("grant_type=authorized_code"));
        assertEquals("AT", grant.accessToken());
        assertEquals(Instant.ofEpochSecond(1700086400L), grant.accessTokenExpiresAt());
        assertEquals("OID", grant.openId());
        assertEquals(java.util.List.of("a", "b"), grant.grantedScopes());
        assertFalse(grant.toString().contains("AT") && grant.toString().contains("RT"), "grant toString is redacted");
    }

    @Test
    void refreshUsesTheRefreshPathAndGrantType() {
        FakeTransport transport = new FakeTransport(s -> FakeTransport.ok(
                "{\"access_token\":\"AT2\",\"access_token_expire_in\":3600,\"refresh_token\":\"RT2\",\"refresh_token_expire_in\":1707776000}"));
        TiktokAuthClient.TokenGrant grant = client(transport).refresh("RT1", NOW);
        assertEquals("/api/v2/token/refresh", transport.sent.get(0).uri().getPath());
        assertTrue(transport.sent.get(0).uri().getRawQuery().contains("grant_type=refresh_token"));
        assertTrue(transport.sent.get(0).uri().getRawQuery().contains("refresh_token=RT1"));
        assertEquals(NOW.plusSeconds(3600), grant.accessTokenExpiresAt(), "a small value is read as a TTL");
        assertTrue(grant.grantedScopes().isEmpty());
    }

    @Test
    void expiryInterpretationIsInOnePlace() {
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), TiktokAuthClient.resolveExpiry(1_700_000_000L, NOW));
        assertEquals(NOW.plusSeconds(7200), TiktokAuthClient.resolveExpiry(7200, NOW));
        assertEquals(NOW, TiktokAuthClient.resolveExpiry(0, NOW));
    }

    @Test
    void rejectionsAndMissingTokensAreErrorsWithoutTheUrl() {
        TiktokApiException rejected = assertThrows(TiktokApiException.class, () ->
                client(new FakeTransport(s -> FakeTransport.apiError(36004001, "rt has expired"))).refresh("RT", NOW));
        assertTrue(rejected.isRejection());
        assertFalse(rejected.getMessage().contains("app-secret-1"));

        assertThrows(TiktokApiException.class, () ->
                client(new FakeTransport(s -> FakeTransport.ok("{\"access_token\":\"\"}"))).exchangeAuthorizationCode("c", NOW));
    }
}
