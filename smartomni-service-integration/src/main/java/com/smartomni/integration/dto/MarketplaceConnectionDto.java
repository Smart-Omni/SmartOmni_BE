package com.smartomni.integration.dto;

import com.smartomni.integration.entity.MarketplaceConnection;

import java.time.Instant;

/** Legacy connection endpoint response: no secret or token column is ever exposed. */
public record MarketplaceConnectionDto(
        Long id,
        String platform,
        String shopId,
        String webhookUrl,
        String status,
        boolean autoImportProducts,
        Instant connectedAt) {

    public static MarketplaceConnectionDto from(MarketplaceConnection c) {
        return new MarketplaceConnectionDto(c.getId(), c.getPlatform().name(), c.getShopId(), c.getWebhookUrl(),
                c.getStatus().name(), c.isAutoImportProducts(), c.getConnectedAt());
    }
}
