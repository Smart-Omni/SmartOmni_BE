package com.smartomni.integration.service.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ItemListResult;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeItemDetail;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeModelDetail;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeWarehouseDetail;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.shopee.ShopeeTenantConnections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopeeProductImportServiceTest {

    private ShopeeTenantConnections tenantConnections;
    private ShopeeCatalogClient catalog;
    private CatalogMappingStore store;
    private PriceChangePolicy priceChanges;
    private TransactionTemplate tx;
    private ShopeeProperties properties;
    private ShopeeProductImportService service;
    private MarketplaceConnection connection;

    @BeforeEach
    void setUp() {
        tenantConnections = mock(ShopeeTenantConnections.class);
        catalog = mock(ShopeeCatalogClient.class);
        store = mock(CatalogMappingStore.class);
        priceChanges = mock(PriceChangePolicy.class);
        tx = TestTx.template();
        properties = new ShopeeProperties();
        ObjectMapper mapper = new ObjectMapper();

        service = new ShopeeProductImportService(tenantConnections, catalog, store, priceChanges, tx, properties, mapper);

        connection = new MarketplaceConnection();
        connection.setId(88L);
        connection.setTenantId(1L);
        connection.setPlatform(MarketplaceConnection.Platform.SHOPEE);
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        connection.setShopId("727720655");
    }

    @Test
    void importProductsSuccessfullyImportsItemsAndModels() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.of(101L));
        when(store.skuLimit()).thenReturn(100);
        when(store.countSkus()).thenReturn(0L);

        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));

        ShopeeItemDetail item = new ShopeeItemDetail(1001L, "T-Shirt", "TS-01", "Cotton", "NORMAL",
                true, "VND", BigDecimal.ZERO, BigDecimal.ZERO, 0, "http://img.jpg");
        when(catalog.getItemBaseInfo(eq(88L), eq(List.of(1001L)))).thenReturn(List.of(item));

        ShopeeModelDetail model = new ShopeeModelDetail(9001L, "Black / L", "TS-BLK-L", "VND",
                new BigDecimal("250000"), new BigDecimal("250000"), 50);
        when(catalog.getModelList(eq(88L), eq(1001L))).thenReturn(List.of(model));

        when(store.findLinkByExternalSku(eq(88L), eq("9001"))).thenReturn(Optional.empty());
        when(store.findSkuByCode(eq("TS-BLK-L"))).thenReturn(Optional.empty());
        when(store.createProduct(anyString(), any(), anyBoolean())).thenReturn(501L);
        when(store.createSku(eq(501L), eq("TS-BLK-L"), eq("Black / L"), any())).thenReturn(701L);

        ImportReport report = service.importProducts(connection);

        assertEquals("completed", report.status());
        assertEquals(1, report.counters().productsSeen());
        assertEquals(1, report.counters().productsCreated());
        assertEquals(1, report.counters().skusCreated());
        assertFalse(report.skuLimitReached());

        verify(store).createProduct(eq("T-Shirt"), any(), eq(false));
        verify(store).createSku(eq(501L), eq("TS-BLK-L"), eq("Black / L"), eq(new BigDecimal("250000")));
        verify(store).initInventoryIfAbsent(eq(701L), eq(50));
        verify(store).insertLink(eq(701L), eq(88L), eq("1001"), eq("9001"), eq("TS-BLK-L"), eq("VND"), eq("NORMAL"), eq("https://shopiz-kappa.vercel.app/product/1001"), eq(new BigDecimal("250000")));
        verify(store).saveProductImage(eq(501L), eq("http://img.jpg"));
        verify(store).finishRun(eq(101L), eq("completed"), any(), eq(100), eq(false), isNull(), anyString());
    }

    @Test
    void importBackfillsMappedSkuWithoutOverwritingExistingBalance() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.of(104L));
        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));
        when(catalog.getItemBaseInfo(88L, List.of(1001L))).thenReturn(List.of(
                new ShopeeItemDetail(1001L, "Shirt", "A1", null, "NORMAL", false, "VND",
                        BigDecimal.TEN, BigDecimal.TEN, 0, null)));
        when(store.findLinkByExternalSku(88L, "1001")).thenReturn(Optional.of(
                new CatalogMappingStore.LinkRef(10L, 701L, 501L, 88L, "1001", "1001", "A1",
                        BigDecimal.TEN, "VND", null)));

        service.importProducts(connection);

        verify(store).initInventoryIfAbsent(701L, 0);
        verify(store, never()).createSku(anyLong(), any(), any(), any());
    }

    @Test
    void importReportsMissingStockWithoutCreatingZeroBalance() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.of(105L));
        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));
        when(catalog.getItemBaseInfo(88L, List.of(1001L))).thenReturn(List.of(
                new ShopeeItemDetail(1001L, "Shirt", "A1", null, "NORMAL", false, "VND",
                        BigDecimal.TEN, BigDecimal.TEN, null, null)));
        when(store.findLinkByExternalSku(88L, "1001")).thenReturn(Optional.of(
                new CatalogMappingStore.LinkRef(10L, 701L, 501L, 88L, "1001", "1001", "A1",
                        BigDecimal.TEN, "VND", null)));

        ImportReport report = service.importProducts(connection);

        assertEquals("STOCK_MISSING", report.items().get(0).outcome());
        assertEquals("completed_with_errors", report.status());
        verify(store, never()).initInventoryIfAbsent(anyLong(), anyInt());
        verify(store).updateLinkMeta(eq(10L), eq("1001"), eq("A1"), eq("VND"), eq("NORMAL"), anyString());
    }

    @Test
    void importLinkExistingSkuWithMissingStockReportsStockMissing() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.of(106L));
        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));
        when(catalog.getItemBaseInfo(88L, List.of(1001L))).thenReturn(List.of(
                new ShopeeItemDetail(1001L, "Shirt", "A1", null, "NORMAL", false, "VND",
                        BigDecimal.TEN, BigDecimal.TEN, null, null)));
        when(store.findLinkByExternalSku(88L, "1001")).thenReturn(Optional.empty());
        when(store.findSkuByCode("A1")).thenReturn(Optional.of(
                new CatalogMappingStore.SkuRef(701L, 501L, "A1")));
        when(store.findLinkBySku(701L, 88L)).thenReturn(Optional.empty());

        ImportReport report = service.importProducts(connection);

        assertEquals(1, report.counters().skusLinked());
        assertEquals("STOCK_MISSING", report.items().get(0).outcome());
        verify(store, never()).initInventoryIfAbsent(anyLong(), anyInt());
        verify(store).insertLink(eq(701L), eq(88L), eq("1001"), eq("1001"), eq("A1"), eq("VND"), eq("NORMAL"),
                anyString(), eq(BigDecimal.TEN));
    }

    @Test
    void importCreatedSkuWithMissingModelStockReportsStockMissing() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.of(107L));
        when(store.skuLimit()).thenReturn(100);
        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));
        when(catalog.getItemBaseInfo(88L, List.of(1001L))).thenReturn(List.of(
                new ShopeeItemDetail(1001L, "T-Shirt", "TS", null, "NORMAL", true, "VND",
                        BigDecimal.ZERO, BigDecimal.ZERO, null, null)));
        when(catalog.getModelList(88L, 1001L)).thenReturn(List.of(
                new ShopeeModelDetail(9001L, "Black", "TS-BLK", "VND", BigDecimal.TEN, BigDecimal.TEN, null),
                new ShopeeModelDetail(9002L, "White", "TS-WHT", "VND", BigDecimal.TEN, BigDecimal.TEN, 7)));
        when(store.findLinkByExternalSku(eq(88L), anyString())).thenReturn(Optional.empty());
        when(store.findSkuByCode(anyString())).thenReturn(Optional.empty());
        when(store.createProduct(anyString(), any(), anyBoolean())).thenReturn(501L);
        when(store.createSku(eq(501L), eq("TS-BLK"), any(), any())).thenReturn(701L);
        when(store.createSku(eq(501L), eq("TS-WHT"), any(), any())).thenReturn(702L);

        ImportReport report = service.importProducts(connection);

        assertEquals(2, report.counters().skusCreated());
        assertEquals(1, report.items().size());
        assertEquals("9001", report.items().get(0).externalSkuId());
        assertEquals("STOCK_MISSING", report.items().get(0).outcome());
        verify(store, never()).initInventoryIfAbsent(eq(701L), anyInt());
        verify(store, times(1)).initInventoryIfAbsent(702L, 7);
    }

    @Test
    void importRejectsConcurrentRun() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.importProducts(connection));
        assertEquals("IMPORT_ALREADY_RUNNING", ex.getErrorCode());
    }

    @Test
    void importStopsWhenPlanSkuLimitReached() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.of(102L));
        when(store.skuLimit()).thenReturn(10);
        when(store.countSkus()).thenReturn(10L); // at limit

        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1001L), 1, false, 1));

        ShopeeItemDetail item = new ShopeeItemDetail(1001L, "T-Shirt", "TS-01", "Cotton", "NORMAL",
                false, "VND", new BigDecimal("100000"), new BigDecimal("100000"), 10, null);
        when(catalog.getItemBaseInfo(eq(88L), eq(List.of(1001L)))).thenReturn(List.of(item));

        when(store.findLinkByExternalSku(eq(88L), eq("1001"))).thenReturn(Optional.empty());
        when(store.findSkuByCode(eq("TS-01"))).thenReturn(Optional.empty());

        ImportReport report = service.importProducts(connection);

        assertEquals("stopped_sku_limit", report.status());
        assertTrue(report.skuLimitReached());
    }

    @Test
    void importHandlesUnmatchedSkuWhenSellerSkuMissing() {
        when(store.openRun(eq(88L), anyInt())).thenReturn(Optional.of(103L));
        when(store.skuLimit()).thenReturn(100);
        when(store.countSkus()).thenReturn(0L);

        when(catalog.getItemList(eq(88L), eq(0), eq(50), any()))
                .thenReturn(new ItemListResult(List.of(1002L), 1, false, 1));

        ShopeeItemDetail item = new ShopeeItemDetail(1002L, "No SKU Mug", null, "Ceramic", "NORMAL",
                false, "VND", new BigDecimal("50000"), new BigDecimal("50000"), 5, null);
        when(catalog.getItemBaseInfo(eq(88L), eq(List.of(1002L)))).thenReturn(List.of(item));

        ImportReport report = service.importProducts(connection);

        assertEquals("completed_with_errors", report.status());
        assertEquals(1, report.counters().skusUnmatched());
        assertEquals(0, report.counters().skusCreated());
    }

    @Test
    void syncWarehousesUpsertsRows() {
        when(catalog.listWarehouses(88L)).thenReturn(List.of(
                new ShopeeWarehouseDetail("WH-1", "Hanoi WH", "Street 1", "Hanoi", "VN"),
                new ShopeeWarehouseDetail("WH-2", "Saigon WH", "Street 2", "HCMC", "VN")
        ));

        int count = service.syncWarehouses(connection);

        assertEquals(2, count);
        verify(store, times(2)).upsertWarehouse(eq(88L), any());
        verify(store).deleteWarehousesNotIn(88L, List.of("WH-1", "WH-2"));
    }

    @Test
    void syncWarehousesKeepsRowsWhenPlatformReturnsNone() {
        when(catalog.listWarehouses(88L)).thenReturn(List.of());

        assertEquals(0, service.syncWarehouses(connection));

        verify(store, never()).deleteWarehousesNotIn(anyLong(), any());
    }
}
