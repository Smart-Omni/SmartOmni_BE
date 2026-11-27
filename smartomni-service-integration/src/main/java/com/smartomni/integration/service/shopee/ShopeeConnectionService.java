package com.smartomni.integration.service.shopee;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.shopee.ShopeeApiException;
import com.smartomni.integration.client.shopee.ShopeeAuthClient;
import com.smartomni.integration.client.shopee.ShopeeOpenApiClient;
import com.smartomni.integration.client.shopee.ShopeeOpenApiClient.ShopeeShopInfo;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.dto.shopee.ShopeeAuthorizationStartDto;
import com.smartomni.integration.dto.shopee.ShopeeCallbackRequest;
import com.smartomni.integration.dto.shopee.ShopeeConnectionDto;
import com.smartomni.integration.dto.shopee.UpdateShopeeSettingsRequest;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.MarketplaceQuotaValidator;
import com.smartomni.integration.service.shopee.ShopeeConnectionStore.ConsumedState;
import com.smartomni.integration.service.shopee.ShopeeConnectionStore.LinkRequest;
import com.smartomni.integration.service.tiktok.TokenCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Base64;
import java.util.Optional;

/**
 * Tenant Admin flow for connecting one Shopee shop to a tenant.
 *
 * <p>Start: creates a single-use, expiring state (stored as SHA-256 hash, bound to tenant, admin and
 * connection) and returns the Shopee authorization URL.
 *
 * <p>Complete: the frontend callback page forwards code, shop_id, and state with the Tenant Admin's JWT.
 * The state is consumed atomically to prevent CSRF and replay attacks. No HTTP call runs inside a database transaction.
 */
@Slf4j
@Service
public class ShopeeConnectionService {

    private final ShopeeProperties properties;
    private final ShopeeConnectionStore store;
    private final ShopeeAuthClient authClient;
    private final ShopeeOpenApiClient openApi;
    private final TokenCipher cipher;
    private final Clock clock;
    private final MarketplaceQuotaValidator quotaValidator;
    private final SecureRandom random = new SecureRandom();

    public ShopeeConnectionService(ShopeeProperties properties,
                                   ShopeeConnectionStore store,
                                   ShopeeAuthClient authClient,
                                   ShopeeOpenApiClient openApi,
                                   TokenCipher cipher,
                                   @Qualifier("integrationBusinessClock") Clock clock) {
        this(properties, store, authClient, openApi, cipher, clock,
                new MarketplaceQuotaValidator((com.smartomni.common.tenant.TenantPlanResolver) null));
    }

    @Autowired
    public ShopeeConnectionService(ShopeeProperties properties,
                                   ShopeeConnectionStore store,
                                   ShopeeAuthClient authClient,
                                   ShopeeOpenApiClient openApi,
                                   TokenCipher cipher,
                                   @Qualifier("integrationBusinessClock") Clock clock,
                                   MarketplaceQuotaValidator quotaValidator) {
        this.properties = properties;
        this.store = store;
        this.authClient = authClient;
        this.openApi = openApi;
        this.cipher = cipher;
        this.clock = clock;
        this.quotaValidator = quotaValidator != null
                ? quotaValidator
                : new MarketplaceQuotaValidator((com.smartomni.common.tenant.TenantPlanResolver) null);
    }

    public ShopeeAuthorizationStartDto start() {
        return start(null);
    }

    public ShopeeAuthorizationStartDto start(String customRedirectUri) {
        Actor actor = requireTenantAdmin();
        if (properties.getPartnerId() == null || properties.getPartnerKey().isBlank()
                || properties.getAuthorizationUrl().isBlank()) {
            throw new BusinessException("Shopee authorization is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE, "SHOPEE_NOT_CONFIGURED");
        }
        quotaValidator.assertCanConnect(actor.tenantId(), store.countActiveConnections(actor.tenantId()));

        String effectiveRedirectUri = resolveAndValidateRedirectUri(customRedirectUri);

        String state = newState();
        Instant expiresAt = clock.instant().plus(properties.getStateTtl());
        try {
            store.prepareStart(actor.tenantId(), actor.userId(), hash(state), expiresAt);
        } catch (DataIntegrityViolationException e) {
            store.prepareStart(actor.tenantId(), actor.userId(), hash(state), expiresAt);
        }

        return new ShopeeAuthorizationStartDto(authorizationUrl(state, effectiveRedirectUri), expiresAt);
    }

    public ShopeeConnectionDto complete(ShopeeCallbackRequest request) {
        Actor actor = requireTenantAdmin();
        String state = request.getState();
        if (state == null || state.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "SHOPEE_STATE_INVALID", "Authorization state is missing or invalid");
        }

        Instant now = clock.instant();
        ConsumedState consumed = store.consumeState(actor.tenantId(), actor.userId(), hash(state), now);
        switch (consumed.outcome()) {
            case INVALID -> throw error(HttpStatus.BAD_REQUEST, "SHOPEE_STATE_INVALID", "Authorization state is missing or invalid");
            case EXPIRED -> throw error(HttpStatus.BAD_REQUEST, "SHOPEE_STATE_EXPIRED", "Authorization state has expired; start again");
            case REUSED -> throw error(HttpStatus.CONFLICT, "SHOPEE_STATE_REUSED", "Authorization state was already used");
            case OK -> { }
        }

        if (request.getError() != null && !request.getError().isBlank()) {
            log.info("Shopee authorization declined: tenant={} connection={}", actor.tenantId(), consumed.connectionId());
            throw error(HttpStatus.BAD_REQUEST, "SHOPEE_AUTHORIZATION_DENIED", "The seller did not authorize SmartOmni");
        }

        if (request.getCode() == null || request.getCode().isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "SHOPEE_CODE_MISSING", "Authorization code is missing");
        }

        Long requestedShopId = parseShopId(request.getShopId());
        ShopeeAuthClient.TokenGrant grant;
        ShopeeShopInfo shopInfo;
        try {
            grant = authClient.exchangeAuthorizationCode(request.getCode(), requestedShopId, now);
            Long effectiveShopId = grant.shopId() != null ? grant.shopId() : requestedShopId;
            if (effectiveShopId == null) {
                throw error(HttpStatus.BAD_REQUEST, "SHOPEE_NO_SHOP", "Shopee response did not contain a valid shop ID");
            }
            shopInfo = openApi.getShopInfo(grant.accessToken(), effectiveShopId);
        } catch (ShopeeApiException e) {
            log.warn("Shopee connection failed: tenant={} {}", actor.tenantId(), e.summary());
            if (e.isTransientFailure()) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "SHOPEE_UNAVAILABLE", "Shopee is temporarily unavailable; start again");
            }
            throw error(HttpStatus.BAD_REQUEST, "SHOPEE_CODE_REJECTED", "Shopee did not accept the authorization: " + e.getMessage());
        }

        LinkRequest link = new LinkRequest(
                String.valueOf(shopInfo.shopId()),
                shopInfo.shopName(),
                shopInfo.region(),
                cipher.encrypt(grant.accessToken()),
                cipher.encrypt(grant.refreshToken()),
                grant.accessTokenExpiresAt(),
                grant.refreshTokenExpiresAt(),
                ConnectionStatus.CONNECTED,
                now
        );

        MarketplaceConnection saved;
        try {
            quotaValidator.assertCanConnect(actor.tenantId(), store.countActiveConnections(actor.tenantId()));
            saved = store.link(actor.tenantId(), consumed.connectionId(), link);
        } catch (DataIntegrityViolationException e) {
            throw error(HttpStatus.CONFLICT, "SHOPEE_SHOP_ALREADY_LINKED", "This Shopee shop is already linked to a SmartOmni tenant");
        }

        return ShopeeConnectionDto.from(saved);
    }

    public Optional<ShopeeConnectionDto> current() {
        Actor actor = requireTenant();
        return store.findCurrent(actor.tenantId()).map(ShopeeConnectionDto::from);
    }

    @Autowired(required = false)
    private com.smartomni.integration.service.catalog.CatalogMappingStore catalogMappingStore;

    public ShopeeConnectionDto disconnect() {
        return disconnect(false);
    }

    public ShopeeConnectionDto disconnect(boolean wipeCatalog) {
        Actor actor = requireTenantAdmin();
        MarketplaceConnection connection = store.findCurrent(actor.tenantId()).orElseThrow(() ->
                error(HttpStatus.NOT_FOUND, "SHOPEE_CONNECTION_NOT_FOUND", "No Shopee connection found"));
        if (wipeCatalog && catalogMappingStore != null) {
            catalogMappingStore.purgeConnectionCatalog(connection.getId());
        }
        return ShopeeConnectionDto.from(store.terminate(actor.tenantId(), connection.getId(),
                ConnectionStatus.DISCONNECTED, null, clock.instant()).orElse(connection));
    }

    public ShopeeConnectionDto updateSettings(UpdateShopeeSettingsRequest request) {
        Actor actor = requireTenantAdmin();
        MarketplaceConnection connection = store.findCurrent(actor.tenantId()).orElseThrow(() ->
                error(HttpStatus.NOT_FOUND, "SHOPEE_CONNECTION_NOT_FOUND", "No Shopee connection found"));
        boolean autoImport = request != null && Boolean.TRUE.equals(request.getAutoImportProducts());
        MarketplaceConnection updated = store.updateSettings(actor.tenantId(), connection.getId(), autoImport);
        return ShopeeConnectionDto.from(updated);
    }

    // ---- helpers

    private String resolveAndValidateRedirectUri(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return properties.getRedirectUri();
        }
        String trimmed = redirectUri.trim();
        if (trimmed.startsWith("smartomni://")
                || trimmed.startsWith("http://localhost:")
                || trimmed.startsWith("http://127.0.0.1:")
                || trimmed.startsWith("https://localhost:")
                || trimmed.startsWith("https://127.0.0.1:")
                || trimmed.startsWith("https://smartomni.vn/")
                || trimmed.matches("^https://[a-zA-Z0-9.-]+\\.smartomni\\.vn(/.*)?$")) {
            return trimmed;
        }
        throw new BusinessException("Invalid redirect_uri. Allowed schemes: smartomni://, localhost, *.smartomni.vn",
                HttpStatus.BAD_REQUEST, "INVALID_REDIRECT_URI");
    }

    private String authorizationUrl(String state) {
        return authorizationUrl(state, properties.getRedirectUri());
    }

    private String authorizationUrl(String state, String redirectUri) {
        String base = properties.getAuthorizationUrl();
        StringBuilder url = new StringBuilder(base).append(base.contains("?") ? '&' : '?')
                .append("partner_id=").append(properties.getPartnerId())
                .append("&redirect_uri=").append(encode(redirectUri))
                .append("&state=").append(encode(state));
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

    private Long parseShopId(String shopIdStr) {
        if (shopIdStr == null || shopIdStr.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(shopIdStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
            throw error(HttpStatus.FORBIDDEN, "TENANT_ADMIN_REQUIRED", "Only a Tenant Admin can manage the Shopee connection");
        }
        return actor;
    }

    private static BusinessException error(HttpStatus status, String code, String message) {
        return new BusinessException(message, status, code);
    }
}
