package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.ShopeeProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token exchange and token refresh client for Shopee Open Platform.
 * Uses HMAC-SHA256 Public API signing on the query parameters and JSON body for payload.
 */
@Component
public class ShopeeAuthClient {

    private final ShopeeProperties properties;
    private final ShopeeHttpTransport transport;
    private final ObjectMapper mapper;

    public ShopeeAuthClient(ShopeeProperties properties, ShopeeHttpTransport transport, ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.mapper = mapper;
    }

    public TokenGrant exchangeAuthorizationCode(String authCode, Long shopId, Instant now) {
        validateConfig();
        long timestamp = now.getEpochSecond();
        String path = properties.getTokenGetPath();
        String sign = ShopeeApiSigner.signPublic(properties.getPartnerKey(), properties.getPartnerId(), path, timestamp);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("partner_id", String.valueOf(properties.getPartnerId()));
        query.put("timestamp", String.valueOf(timestamp));
        query.put("sign", sign);

        ObjectNode body = mapper.createObjectNode();
        body.put("code", authCode);
        body.put("partner_id", properties.getPartnerId());
        if (shopId != null && shopId > 0) {
            body.put("shop_id", shopId);
        }

        ShopeeHttpTransport.Response response = transport.send("POST",
                ShopeeEnvelope.buildUri(properties.getBaseUrl(), path, query),
                Map.of("Content-Type", "application/json"),
                body.toString());

        JsonNode data = ShopeeEnvelope.decode(mapper, response);
        return parseTokenGrant(data, shopId, now);
    }

    public TokenGrant refresh(String refreshToken, Long shopId, Instant now) {
        validateConfig();
        long timestamp = now.getEpochSecond();
        String path = properties.getTokenRefreshPath();
        String sign = ShopeeApiSigner.signPublic(properties.getPartnerKey(), properties.getPartnerId(), path, timestamp);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("partner_id", String.valueOf(properties.getPartnerId()));
        query.put("timestamp", String.valueOf(timestamp));
        query.put("sign", sign);

        ObjectNode body = mapper.createObjectNode();
        body.put("refresh_token", refreshToken);
        body.put("partner_id", properties.getPartnerId());
        if (shopId != null && shopId > 0) {
            body.put("shop_id", shopId);
        }

        ShopeeHttpTransport.Response response = transport.send("POST",
                ShopeeEnvelope.buildUri(properties.getBaseUrl(), path, query),
                Map.of("Content-Type", "application/json"),
                body.toString());

        JsonNode data = ShopeeEnvelope.decode(mapper, response);
        return parseTokenGrant(data, shopId, now);
    }

    private TokenGrant parseTokenGrant(JsonNode data, Long requestedShopId, Instant now) {
        String access = data.path("access_token").asText("");
        String refresh = data.path("refresh_token").asText("");
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new ShopeeApiException("Shopee token response is missing access or refresh token", 200, "missing_token", null, false);
        }

        long expireIn = data.path("expire_in").asLong(14400L); // 4 hours default
        Instant accessExpiresAt = now.plusSeconds(expireIn);
        // Refresh token default TTL 30 days
        Instant refreshExpiresAt = now.plusSeconds(30L * 24 * 3600);

        Long shopId = requestedShopId;
        JsonNode shopIdList = data.path("shop_id_list");
        if (shopIdList.isArray() && !shopIdList.isEmpty()) {
            shopId = shopIdList.get(0).asLong();
        } else if (data.has("shop_id")) {
            shopId = data.path("shop_id").asLong();
        }

        return new TokenGrant(access, refresh, accessExpiresAt, refreshExpiresAt, shopId);
    }

    private void validateConfig() {
        if (properties.getPartnerId() == null || properties.getPartnerKey() == null || properties.getPartnerKey().isBlank()) {
            throw new BusinessException("Shopee partner credentials are not configured",
                    HttpStatus.SERVICE_UNAVAILABLE, "SHOPEE_NOT_CONFIGURED");
        }
    }

    /** Plain tokens live only in memory between network response and cipher encryption. No toString. */
    public record TokenGrant(String accessToken, String refreshToken, Instant accessTokenExpiresAt,
                             Instant refreshTokenExpiresAt, Long shopId) {
        @Override
        public String toString() {
            return "TokenGrant[redacted]";
        }
    }
}
