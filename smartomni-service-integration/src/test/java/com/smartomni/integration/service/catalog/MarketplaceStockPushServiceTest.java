package com.smartomni.integration.service.catalog;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
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
    void tiktokLinkWithoutConnectionFailsInsteadOfLookingSent() {
        when(jdbc.queryForList(anyString(), eq(2L), eq(10L), eq("TIKTOK_SHOP")))
                .thenReturn(List.of(link(5L, null, "tiktok_shop", "P1", "S1")));

        try (var scope = TenantContext.openScope(2L, "admin", null)) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.pushStock(2L, "TIKTOK_SHOP", 10L, 37));
            assertEquals("TIKTOK_LINK_WITHOUT_CONNECTION", ex.getErrorCode());
            assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        }
        verifyNoInteractions(tiktok);
    }

    @Test
    void tiktokFailuresKeepTheirTypeSoTheEndpointAnswersWithAnExplicitCode() {
        when(jdbc.queryForList(anyString(), eq(2L), eq(10L), eq("TIKTOK_SHOP")))
                .thenReturn(List.of(link(5L, 7L, "tiktok_shop", "P1", "S1")));
        // A SKU listed in data.errors surfaces as a rejection from the client: it is not treated as sent.
        doThrow(new TiktokApiException("TikTok rejected SKU inventory update", 200, 12052700, null, false))
                .when(tiktok).updateStock(7L, "P1", "S1", 37);
        doThrow(new BusinessException("TikTok SKU is not on the product", HttpStatus.BAD_REQUEST, "TIKTOK_SKU_NOT_ON_PRODUCT"))
                .when(tiktok).updateStock(7L, "P1", "S1", 38);

        try (var scope = TenantContext.openScope(2L, "admin", null)) {
            TiktokApiException rejected = assertThrows(TiktokApiException.class,
                    () -> service.pushStock(2L, "TIKTOK_SHOP", 10L, 37));
            assertTrue(rejected.isRejection());
            BusinessException invalid = assertThrows(BusinessException.class,
                    () -> service.pushStock(2L, "TIKTOK_SHOP", 10L, 38));
            assertEquals("TIKTOK_SKU_NOT_ON_PRODUCT", invalid.getErrorCode());
        }
    }

    @Test
    void noLinkOnThePlatformIsANoOp() {
        when(jdbc.queryForList(anyString(), eq(2L), eq(10L), eq("TIKTOK_SHOP"))).thenReturn(List.of());

        try (var scope = TenantContext.openScope(2L, "admin", null)) {
            service.pushStock(2L, "TIKTOK_SHOP", 10L, 37);
        }
        verifyNoInteractions(tiktok, shopee);
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
