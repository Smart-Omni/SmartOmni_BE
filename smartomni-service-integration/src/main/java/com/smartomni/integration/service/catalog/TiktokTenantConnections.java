package com.smartomni.integration.service.catalog;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Resolves the caller tenant's TikTok connection for catalog operations; never accepts a tenant or connection id from the client. */
@Component
public class TiktokTenantConnections {

    private final MarketplaceConnectionRepository connections;

    public TiktokTenantConnections(MarketplaceConnectionRepository connections) {
        this.connections = connections;
    }

    /** Admin or Manager of the current tenant; the connection must be CONNECTED. */
    public MarketplaceConnection requireConnected() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Authentication required", HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED");
        }
        String role = TenantContext.getCurrentRole();
        if (!AppConstants.ROLE_TENANT_ADMIN.equalsIgnoreCase(role)
                && !AppConstants.ROLE_ADMIN.equalsIgnoreCase(role)
                && !AppConstants.ROLE_MANAGER.equalsIgnoreCase(role)) {
            throw new BusinessException("Only a Tenant Admin or Manager can sync the TikTok catalog", HttpStatus.FORBIDDEN, "CATALOG_SYNC_FORBIDDEN");
        }
        return connected(tenantId);
    }

    /** For background work that already opened a tenant scope. */
    public MarketplaceConnection connected(Long tenantId) {
        MarketplaceConnection connection = connections
                .findFirstByTenantIdAndPlatformOrderByIdAsc(tenantId, MarketplaceConnection.Platform.TIKTOK_SHOP)
                .orElseThrow(() -> new BusinessException("TikTok Shop is not connected", HttpStatus.CONFLICT, "TIKTOK_NOT_CONNECTED"));
        if (connection.getStatus() != MarketplaceConnection.ConnectionStatus.CONNECTED) {
            throw new BusinessException("TikTok Shop connection needs attention: " + connection.getStatus(),
                    HttpStatus.CONFLICT, "TIKTOK_CONNECTION_" + connection.getStatus().name());
        }
        return connection;
    }
}
