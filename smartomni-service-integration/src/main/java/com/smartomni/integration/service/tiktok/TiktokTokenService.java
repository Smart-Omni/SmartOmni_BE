package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokAuthClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore.TokenSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hands out a valid access token for a connection and refreshes it safely under concurrent requests.
 *
 * <ul>
 *   <li>A per-connection in-JVM lock serialises refreshes inside one instance (the others re-read and reuse
 *       the token that the winner stored).</li>
 *   <li>A database compare-and-swap on {@code token_version} protects across instances: only one rotation
 *       or failure marker can win; a loser re-reads and uses the winner's tokens.</li>
 *   <li>A refresh the platform rejects is never retried. The connection becomes REFRESH_FAILED (needs
 *       reconnect), one WARN is logged and the Tenant Admin is told once. Network errors and 5xx are not
 *       rejections: nothing is marked and the caller may try again later.</li>
 * </ul>
 * Must run with a tenant context (a request, or {@code TenantContext.openScope}).
 */
@Slf4j
@Service
public class TiktokTokenService {

    private final TiktokConnectionStore store;
    private final TiktokAuthClient authClient;
    private final TokenCipher cipher;
    private final ReconnectNotifier notifier;
    private final TiktokProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public TiktokTokenService(TiktokConnectionStore store, TiktokAuthClient authClient, TokenCipher cipher,
                              ReconnectNotifier notifier, TiktokProperties properties,
                              @Qualifier("integrationBusinessClock") Clock clock) {
        this.store = store;
        this.authClient = authClient;
        this.cipher = cipher;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    /** Plain access token for one call. Never log, serialise or return it. */
    public record Credentials(String accessToken, String shopCipher, long tokenVersion) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    public Credentials credentials(Long connectionId) {
        return resolve(connectionId, -1);
    }

    /**
     * The platform rejected the token used with {@code observedVersion}. Refreshes once, unless another caller
     * already rotated it (then that token is returned).
     */
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
            throw reconnectRequired("TIKTOK_CONNECTION_" + snapshot.status().name());
        }
        Instant now = clock.instant();
        if (snapshot.refreshExpiresAt() != null && !snapshot.refreshExpiresAt().isAfter(now)) {
            failRefresh(tenantId, snapshot, "REFRESH_TOKEN_EXPIRED");
            throw reconnectRequired("REFRESH_TOKEN_EXPIRED");
        }
        TiktokAuthClient.TokenGrant grant;
        try {
            grant = authClient.refresh(cipher.decrypt(snapshot.refreshEncrypted()), now);
        } catch (TiktokApiException e) {
            if (e.isTransientFailure()) {
                log.warn("TikTok token refresh unavailable for connection={}: {}", snapshot.id(), e.summary());
                throw new BusinessException("TikTok is temporarily unavailable; try again later",
                        HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_UNAVAILABLE");
            }
            // Rejected. If somebody else already rotated the tokens, theirs are good: do not mark anything.
            TokenSnapshot current = load(tenantId, snapshot.id());
            if (current.version() != snapshot.version() && usable(current, -1)) {
                return credentials(current);
            }
            log.warn("TikTok refresh rejected for connection={}: {}", snapshot.id(), e.summary());
            failRefresh(tenantId, snapshot, "REFRESH_REJECTED");
            throw reconnectRequired("REFRESH_REJECTED");
        }
        String access = cipher.encrypt(grant.accessToken());
        String refresh = cipher.encrypt(grant.refreshToken());
        if (store.rotateTokens(snapshot.id(), snapshot.version(), access, refresh,
                grant.accessTokenExpiresAt(), grant.refreshTokenExpiresAt(), now)) {
            return new Credentials(grant.accessToken(), snapshot.shopCipher(), snapshot.version() + 1);
        }
        // Lost the compare-and-swap: another instance rotated (or the connection was terminated) first.
        TokenSnapshot current = load(tenantId, snapshot.id());
        if (usable(current, -1)) {
            return credentials(current);
        }
        throw reconnectRequired("TIKTOK_CONNECTION_" + current.status().name());
    }

    private void failRefresh(Long tenantId, TokenSnapshot snapshot, String reason) {
        if (store.markTokenFailure(snapshot.id(), snapshot.version(), ConnectionStatus.REFRESH_FAILED, reason, clock.instant())) {
            log.warn("TikTok connection marked REFRESH_FAILED: tenant={} connection={} reason={}", tenantId, snapshot.id(), reason);
            notifier.reconnectRequired(tenantId, snapshot.id(), reason);
        }
    }

    private boolean usable(TokenSnapshot s, long forceIfVersion) {
        return s.status() == ConnectionStatus.CONNECTED && s.accessEncrypted() != null
                && s.accessExpiresAt() != null
                && s.accessExpiresAt().minus(properties.getAccessTokenRefreshSkew()).isAfter(clock.instant())
                && s.version() != forceIfVersion;
    }

    private Credentials credentials(TokenSnapshot s) {
        return new Credentials(cipher.decrypt(s.accessEncrypted()), s.shopCipher(), s.version());
    }

    private TokenSnapshot load(Long tenantId, Long connectionId) {
        return store.loadTokens(tenantId, connectionId).orElseThrow(() ->
                new BusinessException("TikTok connection not found", HttpStatus.NOT_FOUND, "TIKTOK_CONNECTION_NOT_FOUND"));
    }

    private static Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Tenant context required", HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED");
        }
        return tenantId;
    }

    private static BusinessException reconnectRequired(String reason) {
        return new BusinessException("TikTok connection needs to be re-authorized (" + reason + ")",
                HttpStatus.CONFLICT, "TIKTOK_RECONNECT_REQUIRED");
    }
}
