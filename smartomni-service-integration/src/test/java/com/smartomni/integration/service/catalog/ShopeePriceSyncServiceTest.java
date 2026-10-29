package com.smartomni.integration.service.catalog;

import com.smartomni.integration.client.shopee.ShopeeCatalogClient;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ItemListResult;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeItemDetail;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeModelDetail;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.PriceConfig;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopeePriceSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private ShopeeTenantConnections tenantConnections;
    private ShopeeCatalogClient catalog;
    private CatalogMappingStore store;
    private PriceChangePolicy policy;
    private ShopeePriceSyncService service;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(ShopeeTenantConnections.class);
        catalog = mock(ShopeeCatalogClient.class);
        store = mock(CatalogMappingStore.class);
        policy = mock(PriceChangePolicy.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new ShopeePriceSyncService(tenantConnections, catalog, store, policy, TestTx.template(), clock);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
    }

    @Test
    void syncAppliesDuePricesAndRecordsOutcomes() {
        LinkRef link = new LinkRef(1L, 10L, 100L, 88L, "9001", "1001", "SKU-1",
                new BigDecimal("200000"), "VND", NOW.minusSeconds(3600));
        when(store.listLinks(88L)).thenReturn(List.of(link));

        PriceConfig cfg = new PriceConfig("tenant", null, "shopee", true, 15);
        when(store.priceConfigs()).thenReturn(List.of(cfg));

        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));

        ShopeeItemDetail item = new ShopeeItemDetail(1001L, "Item 1", "SKU-1", "Desc", "NORMAL",
                true, "VND", BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
        when(catalog.getItemBaseInfo(eq(88L), eq(List.of(1001L)))).thenReturn(List.of(item));

        ShopeeModelDetail model = new ShopeeModelDetail(9001L, "Model 1", "SKU-1", "VND",
                new BigDecimal("220000"), new BigDecimal("220000"), 10);
        when(catalog.getModelList(eq(88L), eq(1001L))).thenReturn(List.of(model));

        when(policy.apply(eq(link), eq(new BigDecimal("220000")), eq("VND")))
                .thenReturn(PriceChangePolicy.Result.APPLIED);

        ShopeePriceSyncService.Result result = service.sync(connection);

        assertEquals(1, result.considered());
        assertEquals(1, result.applied());
        assertEquals(0, result.heldAnomaly());
        assertEquals(0, result.missing());
    }

    @Test
    void syncHoldsPriceAnomalyWhenThresholdExceeded() {
        LinkRef link = new LinkRef(1L, 10L, 100L, 88L, "9001", "1001", "SKU-1",
                new BigDecimal("200000"), "VND", null); // never synced -> due
        when(store.listLinks(88L)).thenReturn(List.of(link));

        PriceConfig cfg = new PriceConfig("tenant", null, "shopee", true, 15);
        when(store.priceConfigs()).thenReturn(List.of(cfg));

        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));
        ShopeeItemDetail item = new ShopeeItemDetail(1001L, "Item 1", "SKU-1", "Desc", "NORMAL",
                true, "VND", BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
        when(catalog.getItemBaseInfo(eq(88L), eq(List.of(1001L)))).thenReturn(List.of(item));

        ShopeeModelDetail model = new ShopeeModelDetail(9001L, "Model 1", "SKU-1", "VND",
                new BigDecimal("50000"), new BigDecimal("50000"), 10); // drastic drop
        when(catalog.getModelList(eq(88L), eq(1001L))).thenReturn(List.of(model));

        when(policy.apply(eq(link), eq(new BigDecimal("50000")), eq("VND")))
                .thenReturn(PriceChangePolicy.Result.HELD_ANOMALY);

        ShopeePriceSyncService.Result result = service.sync(connection);

        assertEquals(1, result.considered());
        assertEquals(0, result.applied());
        assertEquals(1, result.heldAnomaly());
    }
}
