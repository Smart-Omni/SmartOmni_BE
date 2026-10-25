package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.TiktokProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Signed TikTok Shop OpenAPI adapter shared by the simulator and TikTok: only
 * {@code smartomni.tiktok.open-api-base-url} and the app credentials differ, there is no simulator branch.
 *
 * <p>The request body is serialised to a String once; that exact String is signed and sent.
 */
@Component
public class TiktokOpenApiClient {

    private final TiktokProperties properties;
    private final TiktokHttpTransport transport;
    private final Clock signingClock;
    private final ObjectMapper mapper;

    public TiktokOpenApiClient(TiktokProperties properties, TiktokHttpTransport transport,
                               @Qualifier("tiktokSigningClock") Clock signingClock, ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.signingClock = signingClock;
        this.mapper = mapper;
    }

    /**
     * Performs one signed call and returns the envelope's {@code data}.
     *
     * @param body       raw JSON body or null
     * @param shopCipher sent as {@code shop_cipher} (signed) when not blank
     */
    public JsonNode call(String method, String path, Map<String, String> query, String body,
                         String accessToken, String shopCipher) {
        requireCredentials();
        Map<String, String> params = new TreeMap<>(query == null ? Map.of() : query);
        params.put("app_key", properties.getAppKey());
        params.put("timestamp", Long.toString(signingClock.instant().getEpochSecond()));
        if (shopCipher != null && !shopCipher.isBlank()) {
            params.put("shop_cipher", shopCipher);
        }
        String payload = body == null ? "" : body;
        params.put("sign", TiktokApiSigner.sign(properties.getAppSecret(), path, params, payload));

        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("x-tts-access-token", accessToken);
        headers.put("Content-Type", "application/json");
        TiktokHttpTransport.Response response = transport.send(method,
                TiktokEnvelope.buildUri(properties.getOpenApiBaseUrl(), path, params), headers, payload);
        return TiktokEnvelope.decode(mapper, response);
    }

    /** GET /authorization/202309/shops (official OAS). No shop_cipher: this call discovers it. */
    public List<AuthorizedShop> getAuthorizedShops(String accessToken) {
        JsonNode data = call("GET", "/authorization/202309/shops", Map.of(), null, accessToken, null);
        List<AuthorizedShop> shops = new ArrayList<>();
        for (JsonNode shop : data.path("shops")) {
            shops.add(new AuthorizedShop(text(shop, "id"), text(shop, "cipher"), text(shop, "code"),
                    text(shop, "name"), text(shop, "region"), text(shop, "seller_type")));
        }
        return shops;
    }

    /**
     * Follows {@code next_page_token} until it is empty. Stops with an error on a repeated token or
     * after {@code max-pages}, so a misbehaving server cannot loop forever.
     *
     * @param fetchPage receives the page token ("" for the first page) and returns that page's {@code data}
     */
    public List<JsonNode> fetchAllPages(Function<String, JsonNode> fetchPage) {
        List<JsonNode> pages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String token = "";
        for (int i = 0; i < properties.getMaxPages(); i++) {
            JsonNode page = fetchPage.apply(token);
            pages.add(page);
            String next = page.path("next_page_token").asText("");
            if (next.isEmpty()) {
                return pages;
            }
            if (!seen.add(next)) {
                throw new TiktokApiException("TikTok pagination repeated a page token", 200, null, null, false);
            }
            token = next;
        }
        throw new TiktokApiException("TikTok pagination exceeded " + properties.getMaxPages() + " pages", 200, null, null, false);
    }

    private void requireCredentials() {
        if (properties.getAppKey().isBlank() || properties.getAppSecret().isBlank()) {
            throw new BusinessException("TikTok app credentials are not configured",
                    HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_NOT_CONFIGURED");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    public record AuthorizedShop(String id, String cipher, String code, String name, String region, String sellerType) {
    }
}
