package com.smartomni.integration.service.catalog;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketplaceStockPushServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ShopeeCatalogClient shopee = mock(ShopeeCatalogClient.class);
    private final TiktokCatalogClient tiktok = mock(TiktokCatalogClient.class);
    private final MarketplaceConnectionRepository connections = mock(MarketplaceConnectionRepository.class);
    private final MarketplaceStockPushService service = new MarketplaceStockPushService(jdbc, shopee, tiktok, connections);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void pushesTiktokStockWithoutSettingSessionScopedRlsContext() {
        when(jdbc.queryForList(anyString(), eq(2L), eq(10L), eq("TIKTOK_SHOP")))
                .thenReturn(List.of(link(5L, 7L, "tiktok_shop", "P1", "S1")));

        try (var scope = TenantContext.openScope(2L, "admin", null)) {
            service.pushStock(2L, "TIKTOK_SHOP", 10L, 37);
        }

        verify(tiktok).updateStock(7L, "P1", "S1", 37);
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void pushesShopeeStockAndMarksLinkSyncedWithoutSessionScopedRlsContext() {
        when(jdbc.queryForList(anyString(), eq(2L), eq(10L), eq("SHOPEE")))
                .thenReturn(List.of(link(6L, 8L, "shopee", "1001", "2002")));

        try (var scope = TenantContext.openScope(2L, "admin", null)) {
            service.pushStock(2L, "SHOPEE", 10L, 5);
        }

        verify(shopee).updateStock(8L, 1001L, 2002L, 5);
        verify(jdbc).update(anyString(), eq(6L));
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void refusesToRunWithoutTenantScope() {
        assertThrows(IllegalStateException.class, () -> service.pushStock(2L, "TIKTOK_SHOP", 10L, 37));

        verifyNoInteractions(jdbc, tiktok, shopee);
    }

    @Test
    void refusesToRunInsideAnotherTenantScope() {
        try (var scope = TenantContext.openScope(3L, "admin", null)) {
            assertThrows(IllegalStateException.class, () -> service.pushStock(2L, "TIKTOK_SHOP", 10L, 37));
        }

        verifyNoInteractions(jdbc, tiktok, shopee);
    }

    private static Map<String, Object> link(long id, Long connectionId, String platform, String itemId, String skuId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("connection_id", connectionId);
        row.put("platform", platform);
        row.put("platform_item_id", itemId);
        row.put("external_sku_id", skuId);
        row.put("seller_sku", "SKU-1");
        return row;
    }
}
