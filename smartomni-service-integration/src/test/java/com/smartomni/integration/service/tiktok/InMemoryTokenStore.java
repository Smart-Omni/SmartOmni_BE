package com.smartomni.integration.service.tiktok;

import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;

import java.time.Instant;
import java.util.Optional;

/**
 * Stateful stand-in for the database with the same compare-and-swap semantics as the repository queries
 * (synchronised, version checked). The real SQL is exercised in smartomni-migration-tests.
 */
class InMemoryTokenStore extends TiktokConnectionStore {

    private TokenSnapshot snapshot;
    int rotations;
    int failureMarks;

    InMemoryTokenStore(TokenSnapshot snapshot) {
        super(null, null);
        this.snapshot = snapshot;
    }

    @Override
    public synchronized Optional<TokenSnapshot> loadTokens(Long tenantId, Long connectionId) {
        return Optional.of(snapshot).filter(s -> s.tenantId().equals(tenantId) && s.id().equals(connectionId));
    }

    @Override
    public synchronized boolean rotateTokens(Long id, long expectedVersion, String access, String refresh,
                                             Instant accessExp, Instant refreshExp, Instant now) {
        if (snapshot.version() != expectedVersion || snapshot.status() != ConnectionStatus.CONNECTED) {
            return false;
        }
        snapshot = new TokenSnapshot(snapshot.id(), snapshot.tenantId(), snapshot.status(), snapshot.shopCipher(),
                snapshot.version() + 1, access, refresh, accessExp, refreshExp);
        rotations++;
        return true;
    }

    @Override
    public synchronized boolean markTokenFailure(Long id, long expectedVersion, ConnectionStatus status,
                                                 String errorCode, Instant now) {
        if (snapshot.version() != expectedVersion || snapshot.status() != ConnectionStatus.CONNECTED) {
            return false;
        }
        snapshot = new TokenSnapshot(snapshot.id(), snapshot.tenantId(), status, snapshot.shopCipher(),
                snapshot.version() + 1, null, null, null, null);
        failureMarks++;
        return true;
    }

    synchronized TokenSnapshot snapshot() {
        return snapshot;
    }

    /** Simulates another instance rotating the tokens behind our back. */
    synchronized void externalRotation(String access, String refresh, Instant accessExp) {
        snapshot = new TokenSnapshot(snapshot.id(), snapshot.tenantId(), snapshot.status(), snapshot.shopCipher(),
                snapshot.version() + 1, access, refresh, accessExp, snapshot.refreshExpiresAt());
    }
}
