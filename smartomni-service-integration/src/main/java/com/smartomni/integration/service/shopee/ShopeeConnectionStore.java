package com.smartomni.integration.service.shopee;

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
 * Short database transactions for the Shopee connection. No HTTP call ever runs inside these methods.
 */
@Component
public class ShopeeConnectionStore {

    public enum StateOutcome { OK, INVALID, EXPIRED, REUSED }

    public record ConsumedState(StateOutcome outcome, Long connectionId) {
    }

    /** Token metadata for refresh. Holds ciphertext only. */
    public record TokenSnapshot(Long id, Long tenantId, ConnectionStatus status, long version,
                                String accessEncrypted, String refreshEncrypted,
                                Instant accessExpiresAt, Instant refreshExpiresAt) {
        @Override
        public String toString() {
            return "TokenSnapshot[redacted]";
        }
    }

    /** Result of token exchange + shop info ready to persist. Ciphertext only. */
    public record LinkRequest(String shopId, String shopName, String shopRegion,
                              String accessEncrypted, String refreshEncrypted,
                              Instant accessExpiresAt, Instant refreshExpiresAt,
                              ConnectionStatus status, Instant now) {
        @Override
        public String toString() {
            return "LinkRequest[redacted]";
        }
    }

    private final MarketplaceConnectionRepository connections;
    private final MarketplaceAuthorizationStateRepository states;

    public ShopeeConnectionStore(MarketplaceConnectionRepository connections,
                                 MarketplaceAuthorizationStateRepository states) {
        this.connections = connections;
        this.states = states;
    }

    @Transactional
    public Long prepareStart(Long tenantId, Long userId, String stateHash, Instant expiresAt) {
        MarketplaceConnection connection = connections
                .findFirstByTenantIdAndPlatformOrderByIdAsc(tenantId, MarketplaceConnection.Platform.SHOPEE)
                .orElseGet(() -> {
                    MarketplaceConnection created = new MarketplaceConnection();
                    created.setTenantId(tenantId);
                    created.setPlatform(MarketplaceConnection.Platform.SHOPEE);
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
            throw new BusinessException("This tenant is already connected to a different Shopee shop; disconnect it first",
                    HttpStatus.CONFLICT, "SHOPEE_SHOP_MISMATCH");
        }
        c.setShopId(request.shopId());
        c.setShopName(request.shopName());
        c.setShopRegion(request.shopRegion());
        c.setAccessTokenEncrypted(request.accessEncrypted());
        c.setRefreshTokenEncrypted(request.refreshEncrypted());
        c.setAccessTokenExpiresAt(request.accessExpiresAt());
        c.setRefreshTokenExpiresAt(request.refreshExpiresAt());
        c.setStatus(request.status());
        c.setLastErrorCode(null);
        c.setLastErrorAt(null);
        c.setConnectedAt(request.now());
        c.setTokenVersion(c.getTokenVersion() + 1);
        return connections.saveAndFlush(c);
    }

    @Transactional(readOnly = true)
    public Optional<MarketplaceConnection> findCurrent(Long tenantId) {
        return connections.findFirstByTenantIdAndPlatformOrderByIdAsc(tenantId, MarketplaceConnection.Platform.SHOPEE);
    }

    @Transactional(readOnly = true)
    public Optional<TokenSnapshot> loadTokens(Long tenantId, Long connectionId) {
        return connections.findById(connectionId)
                .filter(c -> tenantId.equals(c.getTenantId()) && c.getPlatform() == MarketplaceConnection.Platform.SHOPEE)
                .map(c -> new TokenSnapshot(c.getId(), c.getTenantId(), c.getStatus(),
                        c.getTokenVersion(), c.getAccessTokenEncrypted(), c.getRefreshTokenEncrypted(),
                        c.getAccessTokenExpiresAt(), c.getRefreshTokenExpiresAt()));
    }

    @Transactional
    public Optional<MarketplaceConnection> terminate(Long tenantId, Long connectionId, ConnectionStatus status,
                                                     String errorCode, Instant now) {
        MarketplaceConnection c = load(tenantId, connectionId);
        if (c.getStatus() == status) {
            return Optional.empty();
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
                .filter(c -> tenantId.equals(c.getTenantId()) && c.getPlatform() == MarketplaceConnection.Platform.SHOPEE)
                .orElseThrow(() -> new BusinessException("Shopee connection not found",
                        HttpStatus.NOT_FOUND, "SHOPEE_CONNECTION_NOT_FOUND"));
    }
}
