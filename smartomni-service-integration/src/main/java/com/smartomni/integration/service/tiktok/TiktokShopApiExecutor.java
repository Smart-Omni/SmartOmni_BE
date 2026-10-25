package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient;
import com.smartomni.integration.config.TiktokProperties;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Calls a shop OpenAPI endpoint on behalf of a connection: valid token and shop cipher come from the
 * connection (so a token can never address another shop), the call is signed by {@link TiktokOpenApiClient}.
 * If the platform answers with a configured "token rejected" code or HTTP status (default 401), the token is refreshed once and the call
 * retried once; there is no further retry.
 */
@Service
public class TiktokShopApiExecutor {

    private final TiktokTokenService tokens;
    private final TiktokOpenApiClient client;
    private final TiktokProperties properties;

    public TiktokShopApiExecutor(TiktokTokenService tokens, TiktokOpenApiClient client, TiktokProperties properties) {
        this.tokens = tokens;
        this.client = client;
        this.properties = properties;
    }

    public JsonNode call(Long connectionId, String method, String path, Map<String, String> query, String body) {
        TiktokTokenService.Credentials credentials = tokens.credentials(connectionId);
        try {
            return client.call(method, path, query, body, credentials.accessToken(), credentials.shopCipher());
        } catch (TiktokApiException e) {
            boolean tokenRejected = (e.getApiCode() != null && properties.getTokenRejectedCodes().contains(e.getApiCode()))
                    || properties.getTokenRejectedHttpStatuses().contains(e.getHttpStatus());
            if (!tokenRejected) {
                throw e;
            }
            TiktokTokenService.Credentials renewed = tokens.refreshAfterRejection(connectionId, credentials.tokenVersion());
            return client.call(method, path, query, body, renewed.accessToken(), renewed.shopCipher());
        }
    }
}
