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

    public ShopeeHttpTransport.Response callRaw(Long connectionId, String method, String path,
                                                Map<String, String> extraQuery, String body) {
        ShopeeTokenService.Credentials creds = tokens.credentials(connectionId);
        return doCallRaw(method, path, extraQuery, body, creds.accessToken(), creds.shopId());
    }

    public JsonNode callMultipart(Long connectionId, String path, String fieldName,
                                  byte[] fileBytes, String filename, String contentType) {
        return callMultipart(connectionId, path, fieldName, fileBytes, filename, contentType, Map.of());
    }

    public JsonNode callMultipart(Long connectionId, String path, String fieldName,
                                  byte[] fileBytes, String filename, String contentType,
                                  Map<String, String> extraParts) {
        ShopeeTokenService.Credentials creds = tokens.credentials(connectionId);
        Instant now = clock.instant();
        long timestamp = now.getEpochSecond();
        String sign = ShopeeApiSigner.signShop(properties.getPartnerKey(), properties.getPartnerId(),
                path, timestamp, creds.accessToken(), creds.shopId());

        Map<String, String> query = new LinkedHashMap<>();
        query.put("partner_id", String.valueOf(properties.getPartnerId()));
        query.put("timestamp", String.valueOf(timestamp));
        query.put("access_token", creds.accessToken());
        query.put("shop_id", String.valueOf(creds.shopId()));
        query.put("sign", sign);

        org.springframework.http.client.MultipartBodyBuilder mb = new org.springframework.http.client.MultipartBodyBuilder();
        if (extraParts != null) {
            extraParts.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    mb.part(k, v);
                }
            });
        }
        mb.part(fieldName, new org.springframework.core.io.ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "proof.jpg";
            }
        }).contentType(contentType != null
                ? org.springframework.http.MediaType.parseMediaType(contentType)
                : org.springframework.http.MediaType.IMAGE_JPEG);

        org.springframework.http.ResponseEntity<String> res = org.springframework.web.client.RestClient.create()
                .post()
                .uri(ShopeeEnvelope.buildUri(properties.getBaseUrl(), path, query))
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(mb.build())
                .retrieve()
                .toEntity(String.class);

        return ShopeeEnvelope.decode(mapper, new ShopeeHttpTransport.Response(
                res.getStatusCode().value(), res.getBody() != null ? res.getBody() : "{}"));
    }

    private JsonNode doCall(String method, String path, Map<String, String> extraQuery, String body,
                            String accessToken, Long shopId) {
        ShopeeHttpTransport.Response response = doCallRaw(method, path, extraQuery, body, accessToken, shopId);
        return ShopeeEnvelope.decode(mapper, response);
    }

    private ShopeeHttpTransport.Response doCallRaw(String method, String path, Map<String, String> extraQuery,
                                                   String body, String accessToken, Long shopId) {
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

        return transport.send(method,
                ShopeeEnvelope.buildUri(properties.getBaseUrl(), path, query),
                headers, body);
    }
}
