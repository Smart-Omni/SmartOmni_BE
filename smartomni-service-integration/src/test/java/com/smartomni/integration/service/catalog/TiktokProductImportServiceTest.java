package com.smartomni.integration.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.*;
import com.smartomni.integration.entity.MarketplaceConnection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiktokProductImportServiceTest {
    @Test
    void importInitializesPerSkuStockAndReportsMissingStock() {
        TiktokCatalogClient catalog = mock(TiktokCatalogClient.class);
        CatalogMappingStore store = mock(CatalogMappingStore.class);
        TiktokProductImportService service = new TiktokProductImportService(
                mock(TiktokTenantConnections.class), catalog, store, mock(PriceChangePolicy.class),
                TestTx.template(), new TiktokProperties(), new ObjectMapper());
        MarketplaceConnection connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(42L);
        Product summary = new Product("P1", "Shirt", "ACTIVATE", null, List.of(), List.of());
        Product detail = new Product("P1", "Shirt", "ACTIVATE", null, List.of(), List.of(
                new Sku("S1", "A1", new Price("VND", "10", null),
                        List.of(new Inventory("HN", 40), new Inventory("HCM", 25)), null, List.of()),
                new Sku("S2", "A2", new Price("VND", "10", null),
                        List.of(new Inventory("HN", 0)), null, List.of()),
                new Sku("S3", "A3", new Price("VND", "10", null), List.of(), null, List.of())));
        when(store.openRun(eq(77L), anyInt())).thenReturn(Optional.of(1L));
        when(store.skuLimit()).thenReturn(100);
        when(catalog.listWarehouses(77L)).thenReturn(List.of());
        when(catalog.getProduct(77L, "P1")).thenReturn(detail);
        doAnswer(call -> {
            Consumer<ProductPage> consumer = call.getArgument(1);
            consumer.accept(new ProductPage("", 1, List.of(summary)));
            return 1;
        }).when(catalog).forEachProductPage(eq(77L), any());
        when(store.findLinkByExternalSku(eq(77L), anyString())).thenReturn(Optional.empty());
        when(store.findSkuByCode(anyString())).thenReturn(Optional.empty());
        when(store.createProduct(anyString(), any(), anyBoolean())).thenReturn(500L);
        when(store.createSku(eq(500L), anyString(), any(), any(BigDecimal.class)))
                .thenReturn(701L, 702L, 703L);

        ImportReport report = service.importProducts(connection);

        verify(store).initInventoryIfAbsent(701L, 65);
        verify(store).initInventoryIfAbsent(702L, 0);
        verify(store, never()).initInventoryIfAbsent(eq(703L), anyInt());
        assertEquals("completed_with_errors", report.status());
        assertEquals("STOCK_MISSING", report.items().get(0).outcome());
    }
}
