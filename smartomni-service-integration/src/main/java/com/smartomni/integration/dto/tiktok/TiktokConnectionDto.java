package com.smartomni.integration.dto.tiktok;

import com.smartomni.integration.entity.MarketplaceConnection;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Public view of a TikTok connection. Deliberately has no token, secret or shop cipher field; entities
 * are never returned from controllers.
 */
public record TiktokConnectionDto(
        Long id,
        String status,
        boolean needsReconnect,
        String shopId,
        String shopName,
        String shopRegion,
        String sellerType,
        List<String> grantedScopes,
        Instant connectedAt,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        String lastErrorCode,
        Instant lastErrorAt) {

    public static TiktokConnectionDto from(MarketplaceConnection c) {
        MarketplaceConnection.ConnectionStatus status = c.getStatus();
        boolean needsReconnect = status == MarketplaceConnection.ConnectionStatus.MISSING_SCOPE
                || status == MarketplaceConnection.ConnectionStatus.REFRESH_FAILED
                || status == MarketplaceConnection.ConnectionStatus.REVOKED;
        String scopes = c.getGrantedScopes();
        return new TiktokConnectionDto(c.getId(), status.name(), needsReconnect, c.getShopId(), c.getShopName(),
                c.getShopRegion(), c.getSellerType(),
                scopes == null || scopes.isBlank() ? List.of() : Arrays.asList(scopes.trim().split("\\s+")),
                c.getConnectedAt(), c.getAccessTokenExpiresAt(), c.getRefreshTokenExpiresAt(),
                c.getLastErrorCode(), c.getLastErrorAt());
    }
}
