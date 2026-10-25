package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.TiktokProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token endpoints (auth_code exchange and refresh). UNVERIFIED shape (contracts/manifest
 * {@code auth.token_get} / {@code auth.token_refresh}, secondary sources): GET on the token host with
 * app_key, app_secret and code/refresh token in the query, unsigned. Every URL here carries secrets,
 * so no exception or log line may include it.
 */
@Component
public class TiktokAuthClient {

    private final TiktokProperties properties;
    private final TiktokHttpTransport transport;
    private final ObjectMapper mapper;

    public TiktokAuthClient(TiktokProperties properties, TiktokHttpTransport transport, ObjectMapper mapper) {
        this.properties = properties;
        this.transport = transport;
        this.mapper = mapper;
    }

    public TokenGrant exchangeAuthorizationCode(String authCode, Instant now) {
        Map<String, String> query = credentials();
        query.put("auth_code", authCode);
        query.put("grant_type", properties.getAuthorizationCodeGrantType());
        return request(properties.getTokenGetPath(), query, now);
    }

    public TokenGrant refresh(String refreshToken, Instant now) {
        Map<String, String> query = credentials();
        query.put("refresh_token", refreshToken);
        query.put("grant_type", properties.getRefreshGrantType());
        return request(properties.getTokenRefreshPath(), query, now);
    }

    private Map<String, String> credentials() {
        if (properties.getAppKey().isBlank() || properties.getAppSecret().isBlank()) {
            throw new BusinessException("TikTok app credentials are not configured",
                    HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_NOT_CONFIGURED");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("app_key", properties.getAppKey());
        query.put("app_secret", properties.getAppSecret());
        return query;
    }

    private TokenGrant request(String path, Map<String, String> query, Instant now) {
        TiktokHttpTransport.Response response = transport.send("GET",
                TiktokEnvelope.buildUri(properties.getTokenBaseUrl(), path, query), Map.of(), null);
        JsonNode data = TiktokEnvelope.decode(mapper, response);
        String access = data.path("access_token").asText("");
        String refresh = data.path("refresh_token").asText("");
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new TiktokApiException("TikTok token response is missing tokens", response.status(), 0, null, false);
        }
        List<String> scopes = new ArrayList<>();
        // UNVERIFIED: no scope field exists in the provisional token contract. Read defensively; agreed
        // with the simulator as "granted_scopes": [..] in data.
        for (JsonNode scope : data.path("granted_scopes")) {
            if (scope.isTextual() && !scope.asText().isBlank()) {
                scopes.add(scope.asText());
            }
        }
        return new TokenGrant(access, refresh,
                resolveExpiry(data.path("access_token_expire_in").asLong(0), now),
                resolveExpiry(data.path("refresh_token_expire_in").asLong(0), now),
                data.path("open_id").asText(null), scopes);
    }

    /**
     * Single place that interprets {@code *_expire_in}. UNVERIFIED: third-party clients report an absolute
     * Unix timestamp (seconds), and the provisional fixture agrees; a small value that cannot be a
     * present-day epoch is read as a TTL in seconds. A missing value yields {@code now} (already stale).
     */
    static Instant resolveExpiry(long value, Instant now) {
        if (value <= 0) {
            return now;
        }
        return value >= 1_000_000_000L ? Instant.ofEpochSecond(value) : now.plusSeconds(value);
    }

    /** Plain tokens live only in memory, between the platform response and encryption. No toString. */
    public record TokenGrant(String accessToken, String refreshToken, Instant accessTokenExpiresAt,
                             Instant refreshTokenExpiresAt, String openId, List<String> grantedScopes) {
        @Override
        public String toString() {
            return "TokenGrant[redacted]";
        }
    }
}
