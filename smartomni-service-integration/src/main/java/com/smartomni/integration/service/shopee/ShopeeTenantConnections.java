package com.smartomni.integration.service.shopee;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller tenant's Shopee connection for catalog and order operations.
 */
@Component
public class ShopeeTenantConnections {

    private final MarketplaceConnectionRepository connections;

    public ShopeeTenantConnections(MarketplaceConnectionRepository connections) {
        this.connections = connections;
    }

    /** Requires Admin or Manager role of current tenant; connection must be CONNECTED. */
    public MarketplaceConnection requireConnected() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Authentication required", HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED");
        }
        String role = TenantContext.getCurrentRole();
        if (!AppConstants.ROLE_TENANT_ADMIN.equalsIgnoreCase(role)
                && !AppConstants.ROLE_ADMIN.equalsIgnoreCase(role)
                && !AppConstants.ROLE_MANAGER.equalsIgnoreCase(role)) {
            throw new BusinessException("Only a Tenant Admin or Manager can access Shopee catalog",
                    HttpStatus.FORBIDDEN, "CATALOG_SYNC_FORBIDDEN");
        }
        return connected(tenantId);
    }

    public MarketplaceConnection connected(Long tenantId) {
        MarketplaceConnection connection = connections
                .findFirstByTenantIdAndPlatformOrderByIdAsc(tenantId, MarketplaceConnection.Platform.SHOPEE)
                .orElseThrow(() -> new BusinessException("Shopee is not connected", HttpStatus.CONFLICT, "SHOPEE_NOT_CONNECTED"));
        if (connection.getStatus() != MarketplaceConnection.ConnectionStatus.CONNECTED) {
            throw new BusinessException("Shopee connection needs attention: " + connection.getStatus(),
                    HttpStatus.CONFLICT, "SHOPEE_CONNECTION_" + connection.getStatus().name());
        }
        return connection;
    }
}
