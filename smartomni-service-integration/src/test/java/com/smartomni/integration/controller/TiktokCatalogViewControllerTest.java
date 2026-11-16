package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.dto.tiktok.TiktokAutoImportReportDto;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Inventory;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Price;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;
import com.smartomni.integration.dto.tiktok.TiktokCatalogItemDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingStore;
import com.smartomni.integration.service.catalog.CatalogMappingStore.MappedSkuView;
import com.smartomni.integration.service.catalog.TiktokAutoImportService;
import com.smartomni.integration.service.catalog.TiktokCatalogService;
import com.smartomni.integration.service.catalog.TiktokTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("TiktokCatalogViewController Unit Tests (D8)")
class TiktokCatalogViewControllerTest {

    private TiktokTenantConnections tenantConnections;
    private TiktokCatalogClient catalogClient;
    private CatalogMappingStore mappingStore;
    private TiktokCatalogService catalogService;
    private TiktokAutoImportService autoImportService;
    private TiktokCatalogViewController controller;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(TiktokTenantConnections.class);
        catalogClient = mock(TiktokCatalogClient.class);
        mappingStore = mock(CatalogMappingStore.class);
        autoImportService = mock(TiktokAutoImportService.class);

        catalogService = new TiktokCatalogService(tenantConnections, catalogClient, mappingStore, null);
        controller = new TiktokCatalogViewController(catalogService, autoImportService);

        connection = new MarketplaceConnection();
        connection.setId(77L);
        connection.setTenantId(1L);
        when(tenantConnections.requireConnected()).thenReturn(connection);
    }

    @Test
    @DisplayName("GET /api/v1/tiktok/catalog returns mapped and unmapped SKUs with currentPrice and stock")
    void listCatalog_ReturnsEnrichedItems() {
        Product p1 = new Product("PROD-1", "Áo thun SmartOmni", "ACTIVATE", null, List.of(), List.of(
                new Sku("SKU-1", "SELLER-1", new Price("VND", "199000", null), List.of(new Inventory("WH-1", 15)), null, List.of()),
                new Sku("SKU-2", "SELLER-2", new Price("VND", "249000", null), List.of(new Inventory("WH-1", 5)), null, List.of())
        ));

        doAnswer(inv -> {
            Consumer<ProductPage> consumer = inv.getArgument(1);
            consumer.accept(new ProductPage("", 1, List.of(p1)));
            return 1;
        }).when(catalogClient).forEachProductPage(eq(77L), any());

        when(mappingStore.listMappedSkus(77L)).thenReturn(List.of(
                new MappedSkuView(301L, 201L, "LOCAL-TEE-S", "SKU-1", "SELLER-1")
        ));

        ResponseEntity<ApiResponse<List<TiktokCatalogItemDto>>> res = controller.listCatalog();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        List<TiktokCatalogItemDto> items = res.getBody().getData();
        assertEquals(2, items.size());

        TiktokCatalogItemDto first = items.get(0);
        assertEquals("PROD-1", first.getTiktokProductId());
        assertEquals("SKU-1", first.getTiktokSkuId());
        assertEquals("SELLER-1", first.getSellerSku());
        assertEquals("Áo thun SmartOmni", first.getProductName());
        assertEquals(new BigDecimal("199000"), first.getCurrentPrice());
        assertEquals(15, first.getStock());
        assertTrue(first.isMapped());
        assertEquals(201L, first.getLocalProductId());
        assertEquals(301L, first.getLocalSkuId());
        assertEquals("LOCAL-TEE-S", first.getLocalSkuCode());

        TiktokCatalogItemDto second = items.get(1);
        assertEquals("SKU-2", second.getTiktokSkuId());
        assertEquals(new BigDecimal("249000"), second.getCurrentPrice());
        assertEquals(5, second.getStock());
        assertFalse(second.isMapped());
        assertNull(second.getLocalSkuId());
    }

    @Test
    @DisplayName("POST /api/v1/tiktok/catalog/auto-import triggers WF-01 auto-import and returns report")
    void autoImport_ManualTrigger_ReturnsReport() {
        TiktokAutoImportReportDto expected = TiktokAutoImportReportDto.builder()
                .shopId("SHOP-42")
                .totalProducts(3)
                .totalSkus(5)
                .importedCount(3)
                .linkedExistingCount(1)
                .skippedCount(1)
                .status("COMPLETED")
                .build();
        when(autoImportService.autoImportForCurrentTenant("SHOP-42")).thenReturn(expected);

        ResponseEntity<ApiResponse<TiktokAutoImportReportDto>> res = controller.autoImport("SHOP-42", null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Auto-import completed", res.getBody().getMessage());
        assertEquals("SHOP-42", res.getBody().getData().getShopId());
        assertEquals(3, res.getBody().getData().getImportedCount());
        assertEquals(1, res.getBody().getData().getLinkedExistingCount());
        assertEquals(1, res.getBody().getData().getSkippedCount());
        assertEquals("COMPLETED", res.getBody().getData().getStatus());
    }
}
