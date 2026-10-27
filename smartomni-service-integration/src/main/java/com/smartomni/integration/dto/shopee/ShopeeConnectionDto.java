package com.smartomni.integration.dto.shopee;

import com.smartomni.integration.entity.MarketplaceConnection;

import java.time.Instant;

/**
 * Public view of a Shopee connection. Never contains tokens or secrets.
 */
public record ShopeeConnectionDto(
        Long id,
        String status,
        boolean needsReconnect,
        String shopId,
        String shopName,
        String shopRegion,
        Instant connectedAt,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        String lastErrorCode,
        Instant lastErrorAt) {

    public static ShopeeConnectionDto from(MarketplaceConnection c) {
        MarketplaceConnection.ConnectionStatus status = c.getStatus();
        boolean needsReconnect = status == MarketplaceConnection.ConnectionStatus.MISSING_SCOPE
                || status == MarketplaceConnection.ConnectionStatus.REFRESH_FAILED
                || status == MarketplaceConnection.ConnectionStatus.REVOKED;
        return new ShopeeConnectionDto(
                c.getId(),
                status.name(),
                needsReconnect,
                c.getShopId(),
                c.getShopName(),
                c.getShopRegion(),
                c.getConnectedAt(),
                c.getAccessTokenExpiresAt(),
                c.getRefreshTokenExpiresAt(),
                c.getLastErrorCode(),
                c.getLastErrorAt());
    }
}
