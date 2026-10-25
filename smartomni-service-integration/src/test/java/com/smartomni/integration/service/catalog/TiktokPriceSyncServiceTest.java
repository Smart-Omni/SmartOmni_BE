package com.smartomni.integration.service.catalog;

import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Price;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.PriceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class TiktokPriceSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    private CatalogMappingStore store;
    private TiktokCatalogClient catalog;
    private TiktokPriceSyncService service;
    private final MarketplaceConnection connection = new MarketplaceConnection();

    @BeforeEach
    void setUp() {
        store = mock(CatalogMappingStore.class);
        catalog = mock(TiktokCatalogClient.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new TiktokPriceSyncService(null, catalog, store, new PriceChangePolicy(store, BigDecimal.valueOf(50), clock), TestTx.template(), clock);
        connection.setId(1L);
    }

    private static LinkRef link(long id, long skuId, String ext, String price, Instant last) {
        return new LinkRef(id, skuId, 100, 1L, ext, "P1", "SKU-" + id, new BigDecimal(price), "VND", last);
    }

    private static Sku sku(String id, String price) {
        return new Sku(id, "S", new Price("VND", price, null), List.of(), null, List.of());
    }

    private void platformReturns(Sku... skus) {
        when(catalog.forEachProductPage(anyLong(), any())).thenAnswer(inv -> {
            Consumer<ProductPage> consumer = inv.getArgument(1);
            consumer.accept(new ProductPage("", skus.length, List.of(new Product("P1", "t", "ACTIVATE", null, null, List.of(skus)))));
            return 1;
        });
    }

    private static PriceConfig tenantEnabled() {
        return new PriceConfig("tenant", null, null, true, null);
    }

    @Test
    void twoSkusOfTheSameProductUpdateIndependently() {
        when(store.listLinks(1L)).thenReturn(List.of(link(10, 1, "A", "100000", null), link(11, 2, "B", "200000", null)));
        when(store.priceConfigs()).thenReturn(List.of(tenantEnabled()));
        platformReturns(sku("A", "110000"), sku("B", "10000"));

        TiktokPriceSyncService.Result result = service.sync(connection);

        assertEquals(1, result.applied());
        assertEquals(1, result.heldAnomaly());
        verify(store).updateLinkPrice(10L, new BigDecimal("110000"), "VND", NOW);
        verify(store, never()).updateLinkPrice(eq(11L), any(), any(), any());
        verify(store).insertPriceHistory(2L, 1L, "B", new BigDecimal("200000"), new BigDecimal("10000"), "VND", true, false);
    }

    @Test
    void anApiErrorLeavesEveryStoredPriceUntouched() {
        when(store.listLinks(1L)).thenReturn(List.of(link(10, 1, "A", "100000", null)));
        when(store.priceConfigs()).thenReturn(List.of(tenantEnabled()));
        when(catalog.forEachProductPage(anyLong(), any())).thenThrow(new TiktokApiException("down", 500, null, null, true));

        TiktokPriceSyncService.Result result = service.sync(connection);

        assertNotNull(result.fetchError());
        assertEquals(1, result.missing());
        verify(store, never()).updateLinkPrice(anyLong(), any(), any(), any());
        verify(store, never()).touchLink(anyLong(), any());
        verify(store, never()).insertPriceHistory(anyLong(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void missingZeroOrUnparsablePricesAreRejectedNotWritten() {
        when(store.listLinks(1L)).thenReturn(List.of(link(10, 1, "A", "100000", null), link(11, 2, "B", "200000", null), link(12, 3, "C", "300000", null)));
        when(store.priceConfigs()).thenReturn(List.of(tenantEnabled()));
        platformReturns(sku("A", "0"), sku("B", "n/a"), new Sku("C", "S", null, List.of(), null, List.of()));

        TiktokPriceSyncService.Result result = service.sync(connection);

        assertEquals(3, result.rejected());
        verify(store, never()).updateLinkPrice(anyLong(), any(), any(), any());
    }

    @Test
    void noConfigOrDisabledConfigMeansNoPlatformCall() {
        when(store.listLinks(1L)).thenReturn(List.of(link(10, 1, "A", "100000", null)));
        when(store.priceConfigs()).thenReturn(List.of());
        assertEquals(0, service.sync(connection).considered());
        when(store.priceConfigs()).thenReturn(List.of(new PriceConfig("tenant", null, null, false, null)));
        assertEquals(0, service.sync(connection).considered());
        verifyNoInteractions(catalog);
    }

    @Test
    void aSkuLevelConfigOverridesTheTenantLevelOne() {
        List<PriceConfig> configs = List.of(tenantEnabled(), new PriceConfig("product", 2L, "tiktok_shop", false, null),
                new PriceConfig("product", 3L, "shopee", true, null));
        assertTrue(TiktokPriceSyncService.effective(1, configs).enabled(), "tenant default applies");
        assertFalse(TiktokPriceSyncService.effective(2, configs).enabled(), "SKU 2 is switched off for TikTok");
        assertTrue(TiktokPriceSyncService.effective(3, configs).enabled(), "a Shopee config for SKU 3 does not apply to TikTok");
    }

    @Test
    void theConfiguredFrequencyDelaysTheNextSync() {
        List<PriceConfig> configs = List.of(new PriceConfig("tenant", null, null, true, 60));
        assertFalse(TiktokPriceSyncService.isDue(link(10, 1, "A", "1", NOW.minusSeconds(30 * 60)), configs, NOW));
        assertTrue(TiktokPriceSyncService.isDue(link(10, 1, "A", "1", NOW.minusSeconds(61 * 60)), configs, NOW));
        assertTrue(TiktokPriceSyncService.isDue(link(10, 1, "A", "1", null), configs, NOW));
    }

    @Test
    void aSkuGoneFromThePlatformIsCountedAndKeepsItsPrice() {
        when(store.listLinks(1L)).thenReturn(List.of(link(10, 1, "A", "100000", null), link(11, 2, "GONE", "5000", null)));
        when(store.priceConfigs()).thenReturn(List.of(tenantEnabled()));
        platformReturns(sku("A", "100000"));

        TiktokPriceSyncService.Result result = service.sync(connection);

        assertEquals(1, result.missing());
        assertEquals(1, result.unchanged());
        verify(store, never()).updateLinkPrice(eq(11L), any(), any(), any());
    }
}
