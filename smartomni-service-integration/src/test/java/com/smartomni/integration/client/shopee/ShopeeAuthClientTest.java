package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.ShopeeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ShopeeAuthClientTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private ShopeeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ShopeeProperties();
        properties.setPartnerId(1001141L);
        properties.setPartnerKey("shopee_mock_secret_key_2026");
        properties.setBaseUrl("http://shopee-simulator.test:8080");
    }

    private ShopeeAuthClient client(FakeShopeeTransport transport) {
        return new ShopeeAuthClient(properties, transport, new ObjectMapper());
    }

    @Test
    void exchangeAuthorizationCodeSendsPublicSignedRequestAndParsesTokens() {
        FakeShopeeTransport transport = new FakeShopeeTransport(s -> FakeShopeeTransport.ok(
                "{\"access_token\":\"AT-shopee-123\",\"refresh_token\":\"RT-shopee-456\",\"expire_in\":14400,\"shop_id_list\":[727720655]}"));

        ShopeeAuthClient.TokenGrant grant = client(transport).exchangeAuthorizationCode("AUTH-CODE-1", 727720655L, NOW);

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("POST", sent.method());
        assertEquals("/api/v2/auth/token/get", sent.uri().getPath());

        String query = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("partner_id=1001141"));
        assertTrue(query.contains("timestamp=" + NOW.getEpochSecond()));
        assertTrue(query.contains("sign="));

        assertTrue(sent.body().contains("\"code\":\"AUTH-CODE-1\""));
        assertTrue(sent.body().contains("\"partner_id\":1001141"));
        assertTrue(sent.body().contains("\"shop_id\":727720655"));

        assertEquals("AT-shopee-123", grant.accessToken());
        assertEquals("RT-shopee-456", grant.refreshToken());
        assertEquals(NOW.plusSeconds(14400), grant.accessTokenExpiresAt());
        assertEquals(727720655L, grant.shopId());
        assertFalse(grant.toString().contains("AT-shopee-123"), "grant toString must redact tokens");
    }

    @Test
    void refreshSendsSignedRefreshRequestAndParsesNewTokens() {
        FakeShopeeTransport transport = new FakeShopeeTransport(s -> FakeShopeeTransport.ok(
                "{\"access_token\":\"AT-new-999\",\"refresh_token\":\"RT-new-888\",\"expire_in\":14400,\"partner_id\":1001141,\"shop_id\":727720655}"));

        ShopeeAuthClient.TokenGrant grant = client(transport).refresh("RT-old-111", 727720655L, NOW);

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("POST", sent.method());
        assertEquals("/api/v2/auth/access_token/get", sent.uri().getPath());
        assertTrue(sent.body().contains("\"refresh_token\":\"RT-old-111\""));

        assertEquals("AT-new-999", grant.accessToken());
        assertEquals("RT-new-888", grant.refreshToken());
        assertEquals(NOW.plusSeconds(14400), grant.accessTokenExpiresAt());
    }

    @Test
    void rejectsInvalidCodeWithoutSecretLeakage() {
        ShopeeApiException thrown = assertThrows(ShopeeApiException.class, () ->
                client(new FakeShopeeTransport(s -> FakeShopeeTransport.shopeeError("invalid_code", "The code is expired or used or invalid.")))
                        .exchangeAuthorizationCode("BAD-CODE", 727720655L, NOW));

        assertEquals("invalid_code", thrown.getErrorCode());
        assertTrue(thrown.isRejection());
        assertFalse(thrown.isTransientFailure());
        assertFalse(thrown.getMessage().contains("shopee_mock_secret_key_2026"));
    }

    @Test
    void throwsWhenConfigNotSet() {
        properties.setPartnerKey("");
        assertThrows(BusinessException.class, () ->
                client(new FakeShopeeTransport(s -> FakeShopeeTransport.ok("{}"))).exchangeAuthorizationCode("C", 1L, NOW));
    }
}
