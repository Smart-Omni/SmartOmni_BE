package com.smartomni.integration.repository;

import com.smartomni.integration.entity.MarketplaceConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@org.springframework.transaction.annotation.Transactional(readOnly = true)
public interface MarketplaceConnectionRepository extends JpaRepository<MarketplaceConnection, Long> {
    Optional<MarketplaceConnection> findByTenantIdAndPlatform(Long tenantId, MarketplaceConnection.Platform platform);
    List<MarketplaceConnection> findByTenantId(Long tenantId);
    List<MarketplaceConnection> findByStatus(MarketplaceConnection.ConnectionStatus status); // dung cho Polling Scheduler (UC-34) va Price Sync (UC-43)

    /** Tolerant of legacy duplicate rows (unlike {@link #findByTenantIdAndPlatform}). */
    Optional<MarketplaceConnection> findFirstByTenantIdAndPlatformOrderByIdAsc(Long tenantId, MarketplaceConnection.Platform platform);

    /** Find connection by TikTok shop_cipher. */
    Optional<MarketplaceConnection> findFirstByShopCipher(String shopCipher);

    /**
     * Compare-and-swap token rotation: succeeds (1) only if nobody rotated since {@code expectedVersion}
     * and the connection is still CONNECTED. Both tokens are replaced in one statement.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MarketplaceConnection c set c.accessTokenEncrypted = :access, c.refreshTokenEncrypted = :refresh, "
            + "c.accessTokenExpiresAt = :accessExpiresAt, c.refreshTokenExpiresAt = :refreshExpiresAt, "
            + "c.tokenVersion = c.tokenVersion + 1, c.lastErrorCode = null, c.lastErrorAt = null, c.updatedAt = :now "
            + "where c.id = :id and c.tokenVersion = :expectedVersion and c.status = :connected")
    int rotateTokens(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
                     @Param("access") String access, @Param("refresh") String refresh,
                     @Param("accessExpiresAt") Instant accessExpiresAt, @Param("refreshExpiresAt") Instant refreshExpiresAt,
                     @Param("now") Instant now, @Param("connected") MarketplaceConnection.ConnectionStatus connected);

    /**
     * Compare-and-swap failure marker: the winner (1) records the failure, wipes the tokens and bumps the
     * version; every other caller gets 0.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MarketplaceConnection c set c.status = :status, c.accessTokenEncrypted = null, "
            + "c.refreshTokenEncrypted = null, c.tokenVersion = c.tokenVersion + 1, "
            + "c.lastErrorCode = :errorCode, c.lastErrorAt = :now, c.updatedAt = :now "
            + "where c.id = :id and c.tokenVersion = :expectedVersion and c.status = :connected")
    int markTokenFailure(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
                         @Param("status") MarketplaceConnection.ConnectionStatus status,
                         @Param("errorCode") String errorCode, @Param("now") Instant now,
                         @Param("connected") MarketplaceConnection.ConnectionStatus connected);
}
