package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.ShopeeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ShopeeOpenApiClientTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private ShopeeProperties properties;
    private Clock clock;

    @BeforeEach
    void setUp() {
        properties = new ShopeeProperties();
        properties.setPartnerId(1001141L);
        properties.setPartnerKey("shopee_mock_secret_key_2026");
        properties.setBaseUrl("http://shopee-simulator.test:8080");
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private ShopeeOpenApiClient client(FakeShopeeTransport transport) {
        return new ShopeeOpenApiClient(properties, transport, clock, new ObjectMapper());
    }

    @Test
    void getShopInfoSendsSignedShopRequestAndParsesResponse() {
        FakeShopeeTransport transport = new FakeShopeeTransport(s -> FakeShopeeTransport.ok(
                "{\"shop_name\":\"SmartOmni Official Flagship\",\"region\":\"VN\",\"status\":\"NORMAL\",\"is_vacation\":false}"));

        ShopeeOpenApiClient.ShopeeShopInfo info = client(transport).getShopInfo("AT-test", 727720655L);

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("GET", sent.method());
        assertEquals("/api/v2/shop/get_shop_info", sent.uri().getPath());

        String query = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("partner_id=1001141"));
        assertTrue(query.contains("access_token=AT-test"));
        assertTrue(query.contains("shop_id=727720655"));
        assertTrue(query.contains("timestamp=" + NOW.getEpochSecond()));
        assertTrue(query.contains("sign="));

        assertEquals(727720655L, info.shopId());
        assertEquals("SmartOmni Official Flagship", info.shopName());
        assertEquals("VN", info.region());
        assertEquals("NORMAL", info.status());
        assertFalse(info.isVacation());
    }

    @Test
    void getShopInfoHandlesShopeeError() {
        FakeShopeeTransport transport = new FakeShopeeTransport(s ->
                FakeShopeeTransport.shopeeError("error_auth", "Invalid access_token."));

        ShopeeApiException thrown = assertThrows(ShopeeApiException.class, () ->
                client(transport).getShopInfo("BAD-AT", 727720655L));

        assertEquals("error_auth", thrown.getErrorCode());
        assertFalse(thrown.isTransientFailure());
    }
}
