package com.smartomni.integration.service.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.client.shopee.ShopeeApiException;
import com.smartomni.integration.client.shopee.ShopeeApiSigner;
import com.smartomni.integration.client.shopee.ShopeeEnvelope;
import com.smartomni.integration.client.shopee.ShopeeHttpTransport;
import com.smartomni.integration.config.ShopeeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes authenticated Shop API calls on behalf of a Shopee connection.
 * Automates Shop API signing (partner_id + path + timestamp + access_token + shop_id)
 * and performs one refresh-and-retry if the token is rejected (error_auth).
 */
@Service
public class ShopeeShopApiExecutor {

    private final ShopeeTokenService tokens;
    private final ShopeeProperties properties;
    private final ShopeeHttpTransport transport;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ShopeeShopApiExecutor(ShopeeTokenService tokens, ShopeeProperties properties,
                                 ShopeeHttpTransport transport,
                                 @Qualifier("shopeeSigningClock") Clock clock,
                                 ObjectMapper mapper) {
        this.tokens = tokens;
        this.properties = properties;
        this.transport = transport;
        this.clock = clock;
        this.mapper = mapper;
    }

    public JsonNode call(Long connectionId, String method, String path, Map<String, String> extraQuery, String body) {
        ShopeeTokenService.Credentials creds = tokens.credentials(connectionId);
        try {
            return doCall(method, path, extraQuery, body, creds.accessToken(), creds.shopId());
        } catch (ShopeeApiException e) {
            boolean isTokenRejected = "error_auth".equalsIgnoreCase(e.getErrorCode()) || e.getHttpStatus() == 401;
            if (!isTokenRejected) {
                throw e;
            }
            ShopeeTokenService.Credentials renewed = tokens.refreshAfterRejection(connectionId, creds.tokenVersion());
            return doCall(method, path, extraQuery, body, renewed.accessToken(), renewed.shopId());
        }
    }

    private JsonNode doCall(String method, String path, Map<String, String> extraQuery, String body,
                            String accessToken, Long shopId) {
        Instant now = clock.instant();
        long timestamp = now.getEpochSecond();
        String sign = ShopeeApiSigner.signShop(properties.getPartnerKey(), properties.getPartnerId(),
                path, timestamp, accessToken, shopId);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("partner_id", String.valueOf(properties.getPartnerId()));
        query.put("timestamp", String.valueOf(timestamp));
        query.put("access_token", accessToken);
        query.put("shop_id", String.valueOf(shopId));
        query.put("sign", sign);
        if (extraQuery != null) {
            query.putAll(extraQuery);
        }

        Map<String, String> headers = body != null && !body.isEmpty()
                ? Map.of("Content-Type", "application/json")
                : Map.of();

        ShopeeHttpTransport.Response response = transport.send(method,
                ShopeeEnvelope.buildUri(properties.getBaseUrl(), path, query),
                headers, body);

        return ShopeeEnvelope.decode(mapper, response);
    }
}
