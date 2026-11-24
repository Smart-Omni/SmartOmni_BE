package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.exception.TiktokApiExceptionHandler;
import com.smartomni.integration.controller.TiktokCatalogController.TiktokPriceUpdateRequest;
import com.smartomni.integration.controller.TiktokCatalogController.TiktokStockUpdateRequest;
import com.smartomni.integration.service.catalog.ImportReport;
import com.smartomni.integration.service.catalog.TiktokCatalogService;
import com.smartomni.integration.service.catalog.TiktokPriceSyncService;
import com.smartomni.integration.service.catalog.TiktokProductImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TiktokCatalogController Unit Tests")
class TiktokCatalogControllerTest {

    private TiktokProductImportService importService;
    private TiktokPriceSyncService priceSyncService;
    private TiktokCatalogService catalogService;
    private TiktokCatalogController controller;

    @BeforeEach
    void setUp() {
        importService = mock(TiktokProductImportService.class);
        priceSyncService = mock(TiktokPriceSyncService.class);
        catalogService = mock(TiktokCatalogService.class);
        controller = new TiktokCatalogController(importService, priceSyncService, catalogService);
    }

    @Test
    @DisplayName("importProducts triggers product import and returns report")
    void importProducts_Success() {
        com.smartomni.integration.service.catalog.CatalogMappingStore.RunCounters counters =
                new com.smartomni.integration.service.catalog.CatalogMappingStore.RunCounters(1, 10, 8, 2, 0, 0, 8, 8, 0, 0);
        ImportReport report = new ImportReport(1L, "SUCCESS", Instant.now(), Instant.now(), counters, 100, false, null, List.of(), 0);
        when(importService.importForCurrentTenant()).thenReturn(report);

        ApiResponse<ImportReport> res = controller.importProducts();

        assertNotNull(res);
        assertEquals(report, res.getData());
        verify(importService).importForCurrentTenant();
    }

    @Test
    @DisplayName("importRuns returns list of recent import reports")
    void importRuns_Success() {
        com.smartomni.integration.service.catalog.CatalogMappingStore.RunCounters counters =
                new com.smartomni.integration.service.catalog.CatalogMappingStore.RunCounters(1, 5, 5, 0, 0, 0, 5, 5, 0, 0);
        ImportReport report = new ImportReport(1L, "SUCCESS", Instant.now(), Instant.now(), counters, 100, false, null, List.of(), 0);
        when(importService.recentRuns(10)).thenReturn(List.of(report));

        ApiResponse<List<ImportReport>> res = controller.importRuns(10);

        assertNotNull(res);
        assertEquals(1, res.getData().size());
        verify(importService).recentRuns(10);
    }

    @Test
    @DisplayName("syncWarehouses returns synced warehouse count")
    void syncWarehouses_Success() {
        when(importService.syncWarehousesForCurrentTenant()).thenReturn(3);

        ApiResponse<Map<String, Integer>> res = controller.syncWarehouses();

        assertNotNull(res);
        assertEquals(3, res.getData().get("warehouses"));
        verify(importService).syncWarehousesForCurrentTenant();
    }

    @Test
    @DisplayName("syncPrices delegates to priceSyncService")
    void syncPrices_Success() {
        TiktokPriceSyncService.Result result = new TiktokPriceSyncService.Result(5, 5, 0, 0, 0, 0, null);
        when(priceSyncService.syncForCurrentTenant()).thenReturn(result);

        ApiResponse<TiktokPriceSyncService.Result> res = controller.syncPrices();

        assertNotNull(res);
        assertEquals(result, res.getData());
        verify(priceSyncService).syncForCurrentTenant();
    }

    @Test
    @DisplayName("updateStock without quantity is a 400 TIKTOK_STOCK_INVALID")
    void updateStock_MissingQuantity() {
        TiktokStockUpdateRequest request = TiktokStockUpdateRequest.builder()
                .productId("PROD-001").skuId("SKU-001").build();

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.updateStock(request));

        assertEquals("TIKTOK_STOCK_INVALID", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("updatePrice with a fractional VND price is a 400 TIKTOK_PRICE_INVALID before any push")
    void updatePrice_FractionalPrice() {
        TiktokPriceUpdateRequest request = TiktokPriceUpdateRequest.builder()
                .productId("PROD-001").skuId("SKU-001").originalPrice(new BigDecimal("159000.50")).build();

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.updatePrice(request));

        assertEquals("TIKTOK_PRICE_INVALID", ex.getErrorCode());
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("Stock and price endpoints answer 400 / 409 / 422 / 503 with explicit codes, never 500")
    void errorsAreMappedToExplicitStatuses() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TiktokApiExceptionHandler(), new GlobalExceptionHandler()).build();
        doThrow(new BusinessException("bad", HttpStatus.BAD_REQUEST, "TIKTOK_SKU_NOT_ON_PRODUCT"))
                .when(catalogService).updateStock(any(), eq("P1"), eq("S-BAD"), any());
        doThrow(new BusinessException("no wh", HttpStatus.CONFLICT, "TIKTOK_SKU_NO_WAREHOUSE"))
                .when(catalogService).updateStock(any(), eq("P1"), eq("S-NOWH"), any());
        doThrow(new TiktokApiException("invalid_input", 400, 40006, "req-1", false))
                .when(catalogService).updateStock(any(), eq("P1"), eq("S-REJ"), any());
        doThrow(new TiktokApiException("down", 503, null, null, true))
                .when(catalogService).updateStock(any(), eq("P1"), eq("S-DOWN"), any());

        assertStock(mvc, "{\"productId\":\"P1\",\"skuId\":\"S1\"}", 400, "TIKTOK_STOCK_INVALID");
        assertStock(mvc, "{\"productId\":\"P1\",\"skuId\":\"S-BAD\",\"quantity\":1}", 400, "TIKTOK_SKU_NOT_ON_PRODUCT");
        assertStock(mvc, "{\"productId\":\"P1\",\"skuId\":\"S-NOWH\",\"quantity\":1}", 409, "TIKTOK_SKU_NO_WAREHOUSE");
        assertStock(mvc, "{\"productId\":\"P1\",\"skuId\":\"S-REJ\",\"quantity\":1}", 422, "TIKTOK_REQUEST_REJECTED");
        assertStock(mvc, "{\"productId\":\"P1\",\"skuId\":\"S-DOWN\",\"quantity\":1}", 503, "TIKTOK_UNAVAILABLE");

        mvc.perform(put("/api/v1/tiktok/products/price").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"P1\",\"skuId\":\"S1\",\"originalPrice\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIKTOK_PRICE_INVALID"));
    }

    private static void assertStock(org.springframework.test.web.servlet.MockMvc mvc, String body, int status, String code)
            throws Exception {
        mvc.perform(put("/api/v1/tiktok/products/stock").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @DisplayName("updateStock pushes stock to TikTok Shop via catalogService")
    void updateStock_Success() {
        TiktokStockUpdateRequest request = TiktokStockUpdateRequest.builder()
                .shopId("SHOP-001")
                .productId("PROD-001")
                .skuId("SKU-001")
                .quantity(50)
                .build();

        ApiResponse<Map<String, Object>> res = controller.updateStock(request);

        assertNotNull(res);
        assertEquals("Stock updated successfully", res.getMessage());
        assertEquals("PROD-001", res.getData().get("productId"));
        assertEquals("SKU-001", res.getData().get("skuId"));
        assertEquals(50, res.getData().get("quantity"));
        verify(catalogService).updateStock("SHOP-001", "PROD-001", "SKU-001", 50);
    }

    @Test
    @DisplayName("updatePrice pushes price to TikTok Shop via catalogService")
    void updatePrice_Success() {
        BigDecimal price = new BigDecimal("199000");
        TiktokPriceUpdateRequest request = TiktokPriceUpdateRequest.builder()
                .shopId("SHOP-001")
                .productId("PROD-001")
                .skuId("SKU-001")
                .originalPrice(price)
                .salePrice(price)
                .build();

        ApiResponse<Map<String, Object>> res = controller.updatePrice(request);

        assertNotNull(res);
        assertEquals("Price updated successfully", res.getMessage());
        assertEquals("PROD-001", res.getData().get("productId"));
        assertEquals("SKU-001", res.getData().get("skuId"));
        assertEquals(price, res.getData().get("price"));
        verify(catalogService).updatePrice("SHOP-001", "PROD-001", "SKU-001", price, price);
    }
}
