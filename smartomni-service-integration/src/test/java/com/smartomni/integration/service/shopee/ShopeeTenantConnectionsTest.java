package com.smartomni.integration.service.shopee;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShopeeTenantConnectionsTest {

    private final MarketplaceConnectionRepository repository = mock(MarketplaceConnectionRepository.class);
    private final ShopeeTenantConnections connections = new ShopeeTenantConnections(repository);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void staffTenantAdminAndAdminCanResolveConnectedShop() {
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setTenantId(42L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        when(repository.findFirstByTenantIdAndPlatformOrderByIdAsc(42L, MarketplaceConnection.Platform.SHOPEE))
                .thenReturn(Optional.of(connection));
        TenantContext.setTenantId(42L);

        for (String role : new String[]{"STAFF", "TENANT_ADMIN", "ADMIN"}) {
            TenantContext.setCurrentRole(role);
            assertSame(connection, connections.requireConnected());
        }
    }

    @Test
    void customerAndLegacyManagerAreForbidden() {
        TenantContext.setTenantId(42L);
        for (String role : new String[]{"CUSTOMER", "MANAGER"}) {
            TenantContext.setCurrentRole(role);
            assertEquals("CATALOG_SYNC_FORBIDDEN",
                    assertThrows(BusinessException.class, connections::requireConnected).getErrorCode());
        }
        verifyNoInteractions(repository);
    }
}
