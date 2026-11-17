package com.smartomni.integration.service.catalog;

import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokAutoImportReportDto;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Inventory;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Price;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.SalesAttribute;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.catalog.CatalogMappingStore.LinkRef;
import com.smartomni.integration.service.catalog.CatalogMappingStore.SkuRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TiktokAutoImportService Unit Tests (WF-01)")
class TiktokAutoImportServiceTest {

    private TiktokTenantConnections tenantConnections;
    private MarketplaceConnectionRepository connectionRepository;
    private TiktokCatalogClient catalogClient;
    private CatalogMappingStore store;
    private TiktokAutoImportService service;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        connectionRepository = mock(MarketplaceConnectionRepository.class);
        catalogClient = mock(TiktokCatalogClient.class);
        store = mock(CatalogMappingStore.class);

        service = new TiktokAutoImportService(
                tenantConnections, connectionRepository, catalogClient, store, null, new TiktokProperties());

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(42L);
        connection.setShopId("SHOP-42");
        connection.setPlatform(MarketplaceConnection.Platform.TIKTOK_SHOP);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);

        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("Creates new Product, Sku(s), Inventory, and Link when no local data exists (with TT- fallback)")
    void autoImport_NewProductsAndSkus_CreatesOncePerProduct() {
        Product summary = new Product("TK-P1", "Áo Khoác", "ACTIVATE", null, List.of(), List.of());
        Product detail = new Product("TK-P1", "Áo Khoác", "ACTIVATE", "Desc", List.of(), List.of(
                new Sku("TK-S1", "JACKET-M", new Price("VND", "350000", null),
                        List.of(new Inventory("WH-1", 10)), null, List.of(new SalesAttribute("Size", "M"))),
                new Sku("TK-S2", "", new Price("VND", "360000", null),
                        List.of(new Inventory("WH-1", 4)), null, List.of(new SalesAttribute("Size", "L")))
        ));

        doAnswer(inv -> {
            Consumer<ProductPage> consumer = inv.getArgument(1);
            consumer.accept(new ProductPage("", 1, List.of(summary)));
            return 1;
        }).when(catalogClient).forEachProductPage(eq(77L), any());
        when(catalogClient.getProduct(77L, "TK-P1")).thenReturn(detail);

        when(store.findLinkByExternalSku(eq(77L), anyString())).thenReturn(Optional.empty());
        when(store.findSkuByCode(anyString())).thenReturn(Optional.empty());
        when(store.createProduct("Áo Khoác", "Desc", false)).thenReturn(1001L);
        when(store.createSku(eq(1001L), eq("JACKET-M"), eq("M"), eq(new BigDecimal("350000")))).thenReturn(2001L);
        when(store.createSku(eq(1001L), eq("TT-TK-S2"), eq("L"), eq(new BigDecimal("360000")))).thenReturn(2002L);

        TiktokAutoImportReportDto report = service.autoImportForCurrentTenant(null);

        assertEquals("SHOP-42", report.getShopId());
        assertEquals(1, report.getTotalProducts());
        assertEquals(2, report.getTotalSkus());
        assertEquals(2, report.getImportedCount());
        assertEquals(0, report.getLinkedExistingCount());
        assertEquals(0, report.getSkippedCount());
        assertEquals("COMPLETED", report.getStatus());

        // Product created only once for both SKUs
        verify(store, times(1)).createProduct("Áo Khoác", "Desc", false);
        verify(store).initInventoryIfAbsent(2001L, 10);
        verify(store).initInventoryIfAbsent(2002L, 4);
        verify(store).insertLink(2001L, 77L, "TK-P1", "TK-S1", "JACKET-M", "VND", "ACTIVATE", null, new BigDecimal("350000"));
        verify(store).insertLink(2002L, 77L, "TK-P1", "TK-S2", null, "VND", "ACTIVATE", null, new BigDecimal("360000"));
        verify(store).applyRlsContext(eq(42L), anyString());
    }

    @Test
    @DisplayName("Automatically links to existing local SKU when seller_sku matches sku_code")
    void autoImport_MatchingSellerSku_LinksExisting() {
        Product detail = new Product("TK-P2", "Quần Jean", "ACTIVATE", null, List.of(), List.of(
                new Sku("TK-S9", "JEAN-32", new Price("VND", "420000", null), List.of(new Inventory("WH-1", 0)), null, List.of())
        ));

        doAnswer(inv -> {
            Consumer<ProductPage> consumer = inv.getArgument(1);
            consumer.accept(new ProductPage("", 1, List.of(detail)));
            return 1;
        }).when(catalogClient).forEachProductPage(eq(77L), any());
        when(catalogClient.getProduct(77L, "TK-P2")).thenReturn(detail);

        when(store.findLinkByExternalSku(77L, "TK-S9")).thenReturn(Optional.empty());
        when(store.findSkuByCode("JEAN-32")).thenReturn(Optional.of(new SkuRef(501L, 401L, "JEAN-32")));
        when(store.findLinkBySku(501L, 77L)).thenReturn(Optional.empty());

        TiktokAutoImportReportDto report = service.autoImportForCurrentTenant("SHOP-42");

        assertEquals(1, report.getTotalProducts());
        assertEquals(1, report.getTotalSkus());
        assertEquals(0, report.getImportedCount());
        assertEquals(1, report.getLinkedExistingCount());
        assertEquals(0, report.getSkippedCount());
        assertEquals("COMPLETED", report.getStatus());

        verify(store, never()).createProduct(any(), any(), anyBoolean());
        verify(store, never()).createSku(anyLong(), any(), any(), any());
        verify(store).insertLink(501L, 77L, "TK-P2", "TK-S9", "JEAN-32", "VND", "ACTIVATE", null, new BigDecimal("420000"));
        verify(store).initInventoryIfAbsent(501L, 0);
    }

    @Test
    @DisplayName("Idempotent re-run skips already mapped SKUs, updates link snapshot, and sets RLS context")
    void autoImport_AlreadyMapped_SkipsWithoutDuplicates() {
        Product detail = new Product("TK-P3", "Nón Kết", "ACTIVATE", null, List.of(), List.of(
                new Sku("TK-S10", "CAP-BLK", new Price("VND", "150000", null),
                        List.of(new Inventory("WH-1", 4), new Inventory("WH-2", 3)), null, List.of())
        ));

        doAnswer(inv -> {
            Consumer<ProductPage> consumer = inv.getArgument(1);
            consumer.accept(new ProductPage("", 1, List.of(detail)));
            return 1;
        }).when(catalogClient).forEachProductPage(eq(77L), any());
        when(catalogClient.getProduct(77L, "TK-P3")).thenReturn(detail);

        LinkRef existingLink = new LinkRef(888L, 501L, 401L, 77L, "TK-S10", "TK-P3", "CAP-BLK",
                new BigDecimal("140000"), "VND", Instant.now());
        when(store.findLinkByExternalSku(77L, "TK-S10")).thenReturn(Optional.of(existingLink));

        try (var scope = TenantContext.openScope(42L, "TENANT_ADMIN", 1L)) {
            TiktokAutoImportReportDto report = service.autoImport(connection);

            assertEquals(1, report.getTotalProducts());
            assertEquals(1, report.getTotalSkus());
            assertEquals(0, report.getImportedCount());
            assertEquals(0, report.getLinkedExistingCount());
            assertEquals(1, report.getSkippedCount());
            assertEquals("COMPLETED", report.getStatus());
        }

        verify(store, never()).createProduct(any(), any(), anyBoolean());
        verify(store, never()).createSku(anyLong(), any(), any(), any());
        verify(store, never()).insertLink(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(store).updateLinkMeta(888L, "TK-P3", "CAP-BLK", "VND", "ACTIVATE", null);
        verify(store).updateLinkPrice(eq(888L), eq(new BigDecimal("150000")), eq("VND"), any(Instant.class));
        verify(store).initInventoryIfAbsent(501L, 7);
        verify(store).applyRlsContext(42L, "tenant_admin");
    }
}
