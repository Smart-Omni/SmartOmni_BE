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

    Optional<MarketplaceConnection> findByIdAndTenantId(Long id, Long tenantId);
    long countByTenantIdAndStatusNot(Long tenantId, MarketplaceConnection.ConnectionStatus status);

    long countByTenantIdAndStatusNotAndPlatformNot(Long tenantId, MarketplaceConnection.ConnectionStatus status,
                                                    MarketplaceConnection.Platform platform);
    List<MarketplaceConnection> findByStatus(MarketplaceConnection.ConnectionStatus status); // dung cho Polling Scheduler (UC-34) va Price Sync (UC-43)

    /** Tolerant of legacy duplicate rows (unlike {@link #findByTenantIdAndPlatform}). */
    Optional<MarketplaceConnection> findFirstByTenantIdAndPlatformOrderByIdAsc(Long tenantId, MarketplaceConnection.Platform platform);

    /** Find connection by TikTok shop_cipher. */
    Optional<MarketplaceConnection> findFirstByShopCipher(String shopCipher);

    /** Find connection by TikTok shop_id. */
    Optional<MarketplaceConnection> findFirstByShopId(String shopId);

    /** Find connection by TikTok seller_open_id. */
    Optional<MarketplaceConnection> findFirstBySellerOpenId(String sellerOpenId);

    Optional<MarketplaceConnection> findFirstByPlatformAndShopCipherAndStatusNotOrderByIdAsc(
            MarketplaceConnection.Platform platform, String shopCipher, MarketplaceConnection.ConnectionStatus status);

    Optional<MarketplaceConnection> findFirstByPlatformAndShopIdAndStatusNotOrderByIdAsc(
            MarketplaceConnection.Platform platform, String shopId, MarketplaceConnection.ConnectionStatus status);

    Optional<MarketplaceConnection> findFirstByPlatformAndSellerOpenIdAndStatusNotOrderByIdAsc(
            MarketplaceConnection.Platform platform, String sellerOpenId, MarketplaceConnection.ConnectionStatus status);

    /**
     * TikTok connection for a shop cipher. A disconnected row keeps its shop identity (audit), so a tenant that
     * once held the shop may still own a row with the same cipher: a TikTok row that is not DISCONNECTED wins.
     * Without one, the first row with that cipher is returned, as before.
     */
    default Optional<MarketplaceConnection> findTiktokByShopCipher(String shopCipher) {
        if (shopCipher == null || shopCipher.isBlank()) return Optional.empty();
        return findFirstByPlatformAndShopCipherAndStatusNotOrderByIdAsc(MarketplaceConnection.Platform.TIKTOK_SHOP,
                shopCipher, MarketplaceConnection.ConnectionStatus.DISCONNECTED)
                .or(() -> findFirstByShopCipher(shopCipher));
    }

    /**
     * TikTok connection for an identifier that may be a shop cipher, a shop id or a seller open id (webhook
     * {@code shop_id}). A TikTok row that is not DISCONNECTED wins for any of the three; without one, the earlier
     * lookup order (cipher, shop id, seller open id, any status) is kept.
     */
    default Optional<MarketplaceConnection> findTiktokByShopIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        MarketplaceConnection.Platform tiktok = MarketplaceConnection.Platform.TIKTOK_SHOP;
        MarketplaceConnection.ConnectionStatus disconnected = MarketplaceConnection.ConnectionStatus.DISCONNECTED;
        return findFirstByPlatformAndShopCipherAndStatusNotOrderByIdAsc(tiktok, identifier, disconnected)
                .or(() -> findFirstByPlatformAndShopIdAndStatusNotOrderByIdAsc(tiktok, identifier, disconnected))
                .or(() -> findFirstByPlatformAndSellerOpenIdAndStatusNotOrderByIdAsc(tiktok, identifier, disconnected))
                .or(() -> findFirstByShopCipher(identifier))
                .or(() -> findFirstByShopId(identifier))
                .or(() -> findFirstBySellerOpenId(identifier));
    }

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
