package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.springframework.http.HttpStatus;
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
    private final MarketplaceConnectionRepository connectionRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public TiktokShopApiExecutor(TiktokTokenService tokens, TiktokOpenApiClient client, TiktokProperties properties,
                                 MarketplaceConnectionRepository connectionRepository) {
        this.tokens = tokens;
        this.client = client;
        this.properties = properties;
        this.connectionRepository = connectionRepository;
    }

    public TiktokShopApiExecutor(TiktokTokenService tokens, TiktokOpenApiClient client, TiktokProperties properties) {
        this(tokens, client, properties, null);
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

    /**
     * Executes an OpenAPI call resolving the connection and tenant context by shopCipher.
     */
    public JsonNode callByShopCipher(String shopCipher, String method, String path, Map<String, String> query, String body) {
        if (connectionRepository == null) {
            throw new IllegalStateException("MarketplaceConnectionRepository is required for callByShopCipher");
        }
        MarketplaceConnection connection = connectionRepository.findFirstByShopCipher(shopCipher)
                .orElseThrow(() -> new BusinessException("TikTok connection not found for shopCipher: " + shopCipher,
                        HttpStatus.NOT_FOUND, "TIKTOK_CONNECTION_NOT_FOUND"));

        if (TenantContext.getTenantId() == null) {
            try (var scope = TenantContext.openScope(connection.getTenantId(), null, null)) {
                return call(connection.getId(), method, path, query, body);
            }
        } else {
            return call(connection.getId(), method, path, query, body);
        }
    }
}
