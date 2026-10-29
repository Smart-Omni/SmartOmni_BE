package com.smartomni.integration.service.shopee;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.shopee.ShopeeApiException;
import com.smartomni.integration.client.shopee.ShopeeAuthClient;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.shopee.ShopeeConnectionStore.TokenSnapshot;
import com.smartomni.integration.service.tiktok.ReconnectNotifier;
import com.smartomni.integration.service.tiktok.TokenCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hands out a valid access token for a Shopee connection and refreshes it safely under concurrent requests.
 * Uses compare-and-swap on token_version and per-connection in-JVM locks.
 */
@Slf4j
@Service
public class ShopeeTokenService {

    private final ShopeeConnectionStore store;
    private final ShopeeAuthClient authClient;
    private final TokenCipher cipher;
    private final ReconnectNotifier notifier;
    private final ShopeeProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ShopeeTokenService(ShopeeConnectionStore store, ShopeeAuthClient authClient, TokenCipher cipher,
                              ReconnectNotifier notifier, ShopeeProperties properties,
                              @Qualifier("integrationBusinessClock") Clock clock) {
        this.store = store;
        this.authClient = authClient;
        this.cipher = cipher;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    /** Plain access token for one call. Never log, serialize or return it. */
    public record Credentials(String accessToken, Long shopId, long tokenVersion) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    public Credentials credentials(Long connectionId) {
        return resolve(connectionId, -1);
    }

    public Credentials refreshAfterRejection(Long connectionId, long observedVersion) {
        return resolve(connectionId, observedVersion);
    }

    private Credentials resolve(Long connectionId, long forceIfVersion) {
        Long tenantId = requireTenant();
        TokenSnapshot snapshot = load(tenantId, connectionId);
        if (usable(snapshot, forceIfVersion)) {
            return credentials(snapshot);
        }
        ReentrantLock lock = locks.computeIfAbsent(connectionId, id -> new ReentrantLock());
        lock.lock();
        try {
            snapshot = load(tenantId, connectionId);
            if (usable(snapshot, forceIfVersion)) {
                return credentials(snapshot);
            }
            return refreshLocked(tenantId, snapshot);
        } finally {
            lock.unlock();
        }
    }

    private Credentials refreshLocked(Long tenantId, TokenSnapshot snapshot) {
        if (snapshot.status() != ConnectionStatus.CONNECTED || snapshot.refreshEncrypted() == null) {
            throw reconnectRequired("SHOPEE_CONNECTION_" + snapshot.status().name());
        }
        Instant now = clock.instant();
        String plainRefresh = cipher.decrypt(snapshot.refreshEncrypted());
        MarketplaceConnection connection = store.findCurrent(tenantId).orElseThrow(() ->
                reconnectRequired("SHOPEE_CONNECTION_NOT_FOUND"));
        Long shopId = parseShopId(connection.getShopId());

        ShopeeAuthClient.TokenGrant grant;
        try {
            grant = authClient.refresh(plainRefresh, shopId, now);
        } catch (ShopeeApiException e) {
            if (e.isTransientFailure()) {
                throw new BusinessException("Shopee token refresh temporarily unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE, "SHOPEE_REFRESH_UNAVAILABLE");
            }
            log.warn("Shopee token refresh rejected: tenant={} connection={} {}", tenantId, snapshot.id(), e.summary());
            store.terminate(tenantId, snapshot.id(), ConnectionStatus.REFRESH_FAILED, "REFRESH_REJECTED", now);
            notifier.reconnectRequired(tenantId, snapshot.id(), "REFRESH_REJECTED");
            throw reconnectRequired("SHOPEE_TOKEN_REJECTED");
        }

        String accessEncrypted = cipher.encrypt(grant.accessToken());
        String refreshEncrypted = cipher.encrypt(grant.refreshToken());
        boolean rotated = store.rotateTokens(snapshot.id(), snapshot.version(), accessEncrypted, refreshEncrypted,
                grant.accessTokenExpiresAt(), grant.refreshTokenExpiresAt(), now);

        if (!rotated) {
            TokenSnapshot updated = load(tenantId, snapshot.id());
            return credentials(updated);
        }
        return new Credentials(grant.accessToken(), shopId, snapshot.version() + 1);
    }

    private boolean usable(TokenSnapshot snapshot, long forceIfVersion) {
        if (snapshot.status() != ConnectionStatus.CONNECTED) {
            return false;
        }
        if (snapshot.accessEncrypted() == null || snapshot.accessExpiresAt() == null) {
            return false;
        }
        if (forceIfVersion >= 0 && snapshot.version() <= forceIfVersion) {
            return false;
        }
        Instant now = clock.instant();
        return snapshot.accessExpiresAt().minus(properties.getAccessTokenRefreshSkew()).isAfter(now);
    }

    private Credentials credentials(TokenSnapshot snapshot) {
        MarketplaceConnection connection = store.findCurrent(snapshot.tenantId()).orElseThrow(() ->
                reconnectRequired("SHOPEE_CONNECTION_NOT_FOUND"));
        Long shopId = parseShopId(connection.getShopId());
        String plain = cipher.decrypt(snapshot.accessEncrypted());
        return new Credentials(plain, shopId, snapshot.version());
    }

    private TokenSnapshot load(Long tenantId, Long connectionId) {
        return store.loadTokens(tenantId, connectionId).orElseThrow(() ->
                reconnectRequired("SHOPEE_CONNECTION_NOT_FOUND"));
    }

    private static Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Authentication required", HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED");
        }
        return tenantId;
    }

    private static BusinessException reconnectRequired(String code) {
        return new BusinessException("Shopee connection needs reauthorization", HttpStatus.CONFLICT, code);
    }

    private static Long parseShopId(String shopIdStr) {
        if (shopIdStr == null || shopIdStr.isBlank()) return 0L;
        try {
            return Long.parseLong(shopIdStr.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
