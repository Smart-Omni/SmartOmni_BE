package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.ShopeeProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shopee OpenAPI client for Shop APIs.
 * Uses HMAC-SHA256 Shop API signing:
 * base_string = partner_id + path + timestamp + access_token + shop_id
 */
@Component
public class ShopeeOpenApiClient {

    private final ShopeeProperties properties;
    private final ShopeeHttpTransport transport;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ShopeeOpenApiClient(ShopeeProperties properties, ShopeeHttpTransport transport,
                               Clock shopeeSigningClock, ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.clock = shopeeSigningClock;
        this.mapper = mapper;
    }

    public ShopeeShopInfo getShopInfo(String accessToken, Long shopId) {
        Instant now = clock.instant();
        long timestamp = now.getEpochSecond();
        String path = properties.getShopInfoPath();
        String sign = ShopeeApiSigner.signShop(properties.getPartnerKey(), properties.getPartnerId(),
                path, timestamp, accessToken, shopId);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("partner_id", String.valueOf(properties.getPartnerId()));
        query.put("timestamp", String.valueOf(timestamp));
        query.put("access_token", accessToken);
        query.put("shop_id", String.valueOf(shopId));
        query.put("sign", sign);

        ShopeeHttpTransport.Response response = transport.send("GET",
                ShopeeEnvelope.buildUri(properties.getBaseUrl(), path, query),
                Map.of(), null);

        JsonNode data = ShopeeEnvelope.decode(mapper, response);
        String shopName = data.path("shop_name").asText("Shopee Shop " + shopId);
        String region = data.path("region").asText("VN");
        String status = data.path("status").asText("NORMAL");
        boolean isVacation = data.path("is_vacation").asBoolean(false);

        return new ShopeeShopInfo(shopId, shopName, region, status, isVacation);
    }

    public record ShopeeShopInfo(Long shopId, String shopName, String region, String status, boolean isVacation) {
    }
}
