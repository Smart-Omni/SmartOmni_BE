package com.smartomni.integration.service.tiktok;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient;
import com.smartomni.integration.client.tiktok.TiktokOpenApiClient.AuthorizedShop;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokAuthorizationStartDto;
import com.smartomni.integration.dto.tiktok.TiktokCallbackRequest;
import com.smartomni.integration.dto.tiktok.TiktokConnectionDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.ConsumedState;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.LinkRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant Admin flow for connecting one TikTok shop to a tenant.
 *
 * <p>Start: creates a single-use, expiring state (stored as SHA-256 hash, bound to tenant, admin and
 * connection) and returns the authorization URL. Complete: the SmartOmni frontend callback page forwards
 * code/state with the Tenant Admin's JWT; the tenant is taken from that JWT, never from the request, and must
 * be the one bound to the state. No HTTP call runs inside a database transaction.
 */
@Slf4j
@Service
public class TiktokConnectionService {

    private final TiktokProperties properties;
    private final TiktokConnectionStore store;
    private final TiktokAuthClient authClient;
    private final TiktokOpenApiClient openApi;
    private final TokenCipher cipher;
    private final ReconnectNotifier notifier;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TiktokConnectionService(TiktokProperties properties, TiktokConnectionStore store, TiktokAuthClient authClient,
                                   TiktokOpenApiClient openApi, TokenCipher cipher, ReconnectNotifier notifier,
                                   @Qualifier("integrationBusinessClock") Clock clock) {
        this.properties = properties;
        this.store = store;
        this.authClient = authClient;
        this.openApi = openApi;
        this.cipher = cipher;
        this.notifier = notifier;
        this.clock = clock;
    }

    public TiktokAuthorizationStartDto start() {
        Actor actor = requireTenantAdmin();
        if (properties.getAppKey().isBlank() || properties.getAppSecret().isBlank()
                || properties.getAuthorizationUrl().isBlank()) {
            throw new BusinessException("TikTok authorization is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_NOT_CONFIGURED");
        }
        String state = newState();
        Instant expiresAt = clock.instant().plus(properties.getStateTtl());
        try {
            store.prepareStart(actor.tenantId(), actor.userId(), hash(state), expiresAt);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent starts created the tenant's connection row at the same time: the second finds it.
            store.prepareStart(actor.tenantId(), actor.userId(), hash(state), expiresAt);
        }
        return new TiktokAuthorizationStartDto(authorizationUrl(state), expiresAt);
    }

    public TiktokConnectionDto complete(TiktokCallbackRequest request) {
        Actor actor = requireTenantAdmin();
        String state = request.getState();
        if (state == null || state.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "TIKTOK_STATE_INVALID", "Authorization state is missing or invalid");
        }
        Instant now = clock.instant();
        // Consume first: a repeated, forged, expired or foreign state stops here, before any token call.
        ConsumedState consumed = store.consumeState(actor.tenantId(), actor.userId(), hash(state), now);
        switch (consumed.outcome()) {
            case INVALID -> throw error(HttpStatus.BAD_REQUEST, "TIKTOK_STATE_INVALID", "Authorization state is missing or invalid");
            case EXPIRED -> throw error(HttpStatus.BAD_REQUEST, "TIKTOK_STATE_EXPIRED", "Authorization state has expired; start again");
            case REUSED -> throw error(HttpStatus.CONFLICT, "TIKTOK_STATE_REUSED", "Authorization state was already used");
            case OK -> { }
        }
        if (request.getError() != null && !request.getError().isBlank()) {
            log.info("TikTok authorization declined: tenant={} connection={}", actor.tenantId(), consumed.connectionId());
            throw error(HttpStatus.BAD_REQUEST, "TIKTOK_AUTHORIZATION_DENIED", "The seller did not authorize SmartOmni");
        }
        if (request.getCode() == null || request.getCode().isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "TIKTOK_CODE_MISSING", "Authorization code is missing");
        }

        TiktokAuthClient.TokenGrant grant;
        AuthorizedShop shop;
        try {
            grant = authClient.exchangeAuthorizationCode(request.getCode(), now);
            shop = singleShop(openApi.getAuthorizedShops(grant.accessToken()));
        } catch (TiktokApiException e) {
            log.warn("TikTok connection failed: tenant={} {}", actor.tenantId(), e.summary());
            if (e.isTransientFailure()) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_UNAVAILABLE", "TikTok is temporarily unavailable; start again");
            }
            throw error(HttpStatus.BAD_REQUEST, "TIKTOK_CODE_REJECTED", "TikTok did not accept the authorization");
        }

        List<String> missing = missingScopes(grant.grantedScopes());
        ConnectionStatus status = missing.isEmpty() ? ConnectionStatus.CONNECTED : ConnectionStatus.MISSING_SCOPE;
        LinkRequest link = new LinkRequest(shop.id(), shop.name(), shop.cipher(), shop.region(), shop.sellerType(),
                grant.openId(), String.join(" ", grant.grantedScopes()),
                cipher.encrypt(grant.accessToken()), cipher.encrypt(grant.refreshToken()),
                grant.accessTokenExpiresAt(), grant.refreshTokenExpiresAt(), status, now);
        MarketplaceConnection saved;
        try {
            saved = store.link(actor.tenantId(), consumed.connectionId(), link);
        } catch (DataIntegrityViolationException e) {
            throw error(HttpStatus.CONFLICT, "TIKTOK_SHOP_ALREADY_LINKED", "This TikTok shop is already linked to a SmartOmni tenant");
        }
        if (status == ConnectionStatus.MISSING_SCOPE) {
            log.warn("TikTok connection granted without required scopes: tenant={} connection={} missingCount={}",
                    actor.tenantId(), saved.getId(), missing.size());
            notifier.reconnectRequired(actor.tenantId(), saved.getId(), "SCOPE_MISSING");
        }
        return TiktokConnectionDto.from(saved);
    }

    public Optional<TiktokConnectionDto> current() {
        Actor actor = requireTenant();
        return store.findCurrent(actor.tenantId()).map(TiktokConnectionDto::from);
    }

    public TiktokConnectionDto disconnect() {
        Actor actor = requireTenantAdmin();
        MarketplaceConnection connection = store.findCurrent(actor.tenantId()).orElseThrow(() ->
                error(HttpStatus.NOT_FOUND, "TIKTOK_CONNECTION_NOT_FOUND", "No TikTok connection"));
        return TiktokConnectionDto.from(store.terminate(actor.tenantId(), connection.getId(),
                ConnectionStatus.DISCONNECTED, null, clock.instant()).orElse(connection));
    }

    /**
     * The seller revoked access (for example a SELLER_DEAUTHORIZATION webhook or the Test Console). Wipes the
     * tokens and tells the Tenant Admin once. Needs the tenant context of the connection's tenant.
     */
    public void markRevoked(Long connectionId) {
        Long tenantId = requireTenant().tenantId();
        Optional<MarketplaceConnection> changed = store.terminate(tenantId, connectionId, ConnectionStatus.REVOKED,
                "REVOKED", clock.instant());
        if (changed.isPresent()) {
            log.warn("TikTok connection revoked: tenant={} connection={}", tenantId, connectionId);
            notifier.reconnectRequired(tenantId, connectionId, "REVOKED");
        }
    }

    // ---- helpers

    private AuthorizedShop singleShop(List<AuthorizedShop> shops) {
        if (shops.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "TIKTOK_NO_SHOP", "The authorization did not include any shop");
        }
        if (shops.size() > 1) {
            // One TikTok shop per tenant: never pick one silently.
            throw error(HttpStatus.BAD_REQUEST, "TIKTOK_MULTIPLE_SHOPS", "Authorize exactly one TikTok shop per SmartOmni tenant");
        }
        AuthorizedShop shop = shops.get(0);
        if (shop.id() == null || shop.id().isBlank() || shop.cipher() == null || shop.cipher().isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "TIKTOK_NO_SHOP", "The authorized shop has no id or cipher");
        }
        return shop;
    }

    private List<String> missingScopes(List<String> granted) {
        Set<String> have = new HashSet<>(granted);
        List<String> missing = new ArrayList<>();
        for (String required : properties.getRequiredScopes()) {
            if (!have.contains(required)) {
                missing.add(required);
            }
        }
        return missing;
    }

    private String authorizationUrl(String state) {
        String base = properties.getAuthorizationUrl();
        StringBuilder url = new StringBuilder(base).append(base.contains("?") ? '&' : '?')
                .append(encode(properties.getAuthorizationAppKeyParam())).append('=').append(encode(properties.getAppKey()))
                .append('&').append(encode(properties.getAuthorizationStateParam())).append('=').append(encode(state));
        if (!properties.getRedirectUri().isBlank()) {
            url.append('&').append(encode(properties.getAuthorizationRedirectUriParam())).append('=')
                    .append(encode(properties.getRedirectUri()));
        }
        return url.toString();
    }

    private String newState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String state) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record Actor(Long tenantId, Long userId) {
    }

    private static Actor requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw error(HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED", "Authentication required");
        }
        return new Actor(tenantId, TenantContext.getCurrentUserId());
    }

    private static Actor requireTenantAdmin() {
        Actor actor = requireTenant();
        String role = TenantContext.getCurrentRole();
        if (actor.userId() == null || (!AppConstants.ROLE_TENANT_ADMIN.equalsIgnoreCase(role)
                && !AppConstants.ROLE_ADMIN.equalsIgnoreCase(role))) {
            throw error(HttpStatus.FORBIDDEN, "TENANT_ADMIN_REQUIRED", "Only a Tenant Admin can manage the TikTok connection");
        }
        return actor;
    }

    private static BusinessException error(HttpStatus status, String code, String message) {
        return new BusinessException(message, status, code);
    }
}
