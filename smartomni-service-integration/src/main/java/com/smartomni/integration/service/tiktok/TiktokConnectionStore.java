package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.entity.MarketplaceAuthorizationState;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.repository.MarketplaceAuthorizationStateRepository;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Short database transactions for the TikTok connection. No HTTP call ever happens inside one of these
 * methods. The tenant comes from {@code TenantContext} (RLS) and is double checked on every read.
 */
@Component
public class TiktokConnectionStore {

    public enum StateOutcome { OK, INVALID, EXPIRED, REUSED }

    public record ConsumedState(StateOutcome outcome, Long connectionId) {
    }

    /** Token metadata for refresh. Holds ciphertext only; no toString on purpose. */
    public record TokenSnapshot(Long id, Long tenantId, ConnectionStatus status, String shopCipher, long version,
                                String accessEncrypted, String refreshEncrypted,
                                Instant accessExpiresAt, Instant refreshExpiresAt) {
        @Override
        public String toString() {
            return "TokenSnapshot[redacted]";
        }
    }

    /** Result of Get Authorized Shops + token exchange, ready to persist. Ciphertext only. */
    public record LinkRequest(String shopId, String shopName, String shopCipher, String shopRegion, String sellerType,
                              String sellerOpenId, String grantedScopes, String accessEncrypted, String refreshEncrypted,
                              Instant accessExpiresAt, Instant refreshExpiresAt, ConnectionStatus status, Instant now) {
        @Override
        public String toString() {
            return "LinkRequest[redacted]";
        }
    }

    private final MarketplaceConnectionRepository connections;
    private final MarketplaceAuthorizationStateRepository states;

    public TiktokConnectionStore(MarketplaceConnectionRepository connections,
                                 MarketplaceAuthorizationStateRepository states) {
        this.connections = connections;
        this.states = states;
    }

    /**
     * Finds (or creates as DISCONNECTED placeholder) the tenant's single TikTok connection row and stores a
     * state bound to tenant, user and that connection.
     */
    @Transactional
    public Long prepareStart(Long tenantId, Long userId, String stateHash, Instant expiresAt) {
        MarketplaceConnection connection = connections
                .findFirstByTenantIdAndPlatformOrderByIdAsc(tenantId, MarketplaceConnection.Platform.TIKTOK_SHOP)
                .orElseGet(() -> {
                    MarketplaceConnection created = new MarketplaceConnection();
                    created.setTenantId(tenantId);
                    created.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
                    created.setStatus(ConnectionStatus.DISCONNECTED);
                    return connections.saveAndFlush(created);
                });
        MarketplaceAuthorizationState state = new MarketplaceAuthorizationState();
        state.setTenantId(tenantId);
        state.setConnectionId(connection.getId());
        state.setUserId(userId);
        state.setStateHash(stateHash);
        state.setExpiresAt(expiresAt);
        states.saveAndFlush(state);
        return connection.getId();
    }

    /**
     * Single-use consumption. Wrong tenant, wrong user and unknown state are indistinguishable
     * ({@code INVALID}); another tenant's state is invisible under RLS anyway.
     */
    @Transactional
    public ConsumedState consumeState(Long tenantId, Long userId, String stateHash, Instant now) {
        int updated = states.consume(tenantId, stateHash, userId, now);
        Optional<MarketplaceAuthorizationState> state = states.findByTenantIdAndStateHash(tenantId, stateHash);
        if (updated == 1) {
            return new ConsumedState(StateOutcome.OK, state.orElseThrow().getConnectionId());
        }
        if (state.isEmpty() || !userId.equals(state.get().getUserId())) {
            return new ConsumedState(StateOutcome.INVALID, null);
        }
        if (state.get().getConsumedAt() != null) {
            return new ConsumedState(StateOutcome.REUSED, null);
        }
        return new ConsumedState(state.get().getExpiresAt().isAfter(now) ? StateOutcome.INVALID : StateOutcome.EXPIRED, null);
    }

    @Transactional
    public MarketplaceConnection link(Long tenantId, Long connectionId, LinkRequest request) {
        MarketplaceConnection c = load(tenantId, connectionId);
        boolean occupied = c.getStatus() != ConnectionStatus.DISCONNECTED;
        if (occupied && c.getShopId() != null && !c.getShopId().equals(request.shopId())) {
            throw new BusinessException("This tenant is already connected to a different TikTok shop; disconnect it first",
                    HttpStatus.CONFLICT, "TIKTOK_SHOP_MISMATCH");
        }
        c.setShopId(request.shopId());
        c.setShopName(request.shopName());
        c.setShopCipher(request.shopCipher());
        c.setShopRegion(request.shopRegion());
        c.setSellerType(request.sellerType());
        c.setSellerOpenId(request.sellerOpenId());
        c.setGrantedScopes(request.grantedScopes());
        c.setAccessTokenEncrypted(request.accessEncrypted());
        c.setRefreshTokenEncrypted(request.refreshEncrypted());
        c.setAccessTokenExpiresAt(request.accessExpiresAt());
        c.setRefreshTokenExpiresAt(request.refreshExpiresAt());
        c.setStatus(request.status());
        c.setLastErrorCode(request.status() == ConnectionStatus.MISSING_SCOPE ? "MISSING_SCOPE" : null);
        c.setLastErrorAt(request.status() == ConnectionStatus.MISSING_SCOPE ? request.now() : null);
        c.setConnectedAt(request.now());
        c.setTokenVersion(c.getTokenVersion() + 1);
        return connections.saveAndFlush(c);
    }

    @Transactional(readOnly = true)
    public Optional<MarketplaceConnection> findCurrent(Long tenantId) {
        return connections.findFirstByTenantIdAndPlatformOrderByIdAsc(tenantId, MarketplaceConnection.Platform.TIKTOK_SHOP);
    }

    @Transactional(readOnly = true)
    public Optional<TokenSnapshot> loadTokens(Long tenantId, Long connectionId) {
        return connections.findById(connectionId)
                .filter(c -> tenantId.equals(c.getTenantId()) && c.getPlatform() == MarketplaceConnection.Platform.TIKTOK_SHOP)
                .map(c -> new TokenSnapshot(c.getId(), c.getTenantId(), c.getStatus(), c.getShopCipher(),
                        c.getTokenVersion(), c.getAccessTokenEncrypted(), c.getRefreshTokenEncrypted(),
                        c.getAccessTokenExpiresAt(), c.getRefreshTokenExpiresAt()));
    }

    public boolean rotateTokens(Long connectionId, long expectedVersion, String access, String refresh,
                                Instant accessExpiresAt, Instant refreshExpiresAt, Instant now) {
        return connections.rotateTokens(connectionId, expectedVersion, access, refresh, accessExpiresAt,
                refreshExpiresAt, now, ConnectionStatus.CONNECTED) == 1;
    }

    public boolean markTokenFailure(Long connectionId, long expectedVersion, ConnectionStatus status,
                                    String errorCode, Instant now) {
        return connections.markTokenFailure(connectionId, expectedVersion, status, errorCode, now,
                ConnectionStatus.CONNECTED) == 1;
    }

    /** Disconnect or revoke: wipes tokens, keeps the shop identity for audit. */
    @Transactional
    public Optional<MarketplaceConnection> terminate(Long tenantId, Long connectionId, ConnectionStatus status,
                                                     String errorCode, Instant now) {
        MarketplaceConnection c = load(tenantId, connectionId);
        if (c.getStatus() == status) {
            return Optional.empty(); // already there: no second alert, no second write
        }
        c.setStatus(status);
        c.setAccessTokenEncrypted(null);
        c.setRefreshTokenEncrypted(null);
        c.setAccessTokenExpiresAt(null);
        c.setRefreshTokenExpiresAt(null);
        c.setTokenVersion(c.getTokenVersion() + 1);
        c.setLastErrorCode(errorCode);
        c.setLastErrorAt(errorCode == null ? null : now);
        return Optional.of(connections.saveAndFlush(c));
    }

    private MarketplaceConnection load(Long tenantId, Long connectionId) {
        return connections.findById(connectionId)
                .filter(c -> tenantId.equals(c.getTenantId()) && c.getPlatform() == MarketplaceConnection.Platform.TIKTOK_SHOP)
                .orElseThrow(() -> new BusinessException("TikTok connection not found",
                        HttpStatus.NOT_FOUND, "TIKTOK_CONNECTION_NOT_FOUND"));
    }
}
