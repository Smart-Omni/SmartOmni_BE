package com.smartomni.integration.service.catalog;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TiktokTenantConnectionsTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void tenantAdminCanResolveConnectedShop() {
        MarketplaceConnectionRepository repository = mock(MarketplaceConnectionRepository.class);
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setTenantId(42L);
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        when(repository.findFirstByTenantIdAndPlatformOrderByIdAsc(42L, MarketplaceConnection.Platform.TIKTOK_SHOP))
                .thenReturn(Optional.of(connection));
        TenantContext.setTenantId(42L);
        TenantContext.setCurrentRole("TENANT_ADMIN");

        assertSame(connection, new TiktokTenantConnections(repository).requireConnected());
        verify(repository).findFirstByTenantIdAndPlatformOrderByIdAsc(42L, MarketplaceConnection.Platform.TIKTOK_SHOP);
    }

    @Test
    void customerCannotResolveConnectedShop() {
        MarketplaceConnectionRepository repository = mock(MarketplaceConnectionRepository.class);
        TenantContext.setTenantId(42L);
        TenantContext.setCurrentRole("CUSTOMER");

        BusinessException error = assertThrows(BusinessException.class,
                () -> new TiktokTenantConnections(repository).requireConnected());
        assertEquals("CATALOG_SYNC_FORBIDDEN", error.getErrorCode());
        verifyNoInteractions(repository);
    }
}