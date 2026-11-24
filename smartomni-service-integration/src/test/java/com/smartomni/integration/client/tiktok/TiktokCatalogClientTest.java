package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TiktokCatalogClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, String>> queries = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private TiktokProperties properties;
    private int failOnCall = -1;

    @BeforeEach
    void setUp() {
        properties = new TiktokProperties();
        properties.setProductPageSize(2);
        properties.setMaxPages(5);
    }

    private TiktokCatalogClient client(java.util.function.Function<String, String> tokenToNext) {
        TiktokShopApiExecutor executor = new TiktokShopApiExecutor(null, null, null) {
            @Override
            public JsonNode call(Long connectionId, String method, String path, Map<String, String> query, String body) {
                calls.add(method + " " + path + " body=" + body);
                queries.add(Map.copyOf(query));
                if (calls.size() == failOnCall) {
                    throw new TiktokApiException("boom", 500, null, null, true);
                }
                try {
                    if (path.startsWith("/product/202502/products/search")) {
                        String token = query.getOrDefault("page_token", "");
                        return mapper.readTree("{\"next_page_token\":\"" + tokenToNext.apply(token) + "\",\"products\":[{\"id\":\"P" + calls.size() + "\",\"status\":\"ACTIVATE\"}]}");
                    }
                    if (path.startsWith("/logistics/")) {
                        return mapper.readTree("{\"warehouses\":[{\"id\":\"W1\"}]}");
                    }
                    return mapper.readTree("{\"id\":\"1\",\"title\":\"t\"}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        return new TiktokCatalogClient(executor, properties, mapper);
    }

    @Test
    void searchSendsRequiredPageSizeAndTheOpaquePageTokenNeverAnIndex() {
        TiktokCatalogClient client = client(token -> token.isEmpty() ? "TOKEN-2" : token.equals("TOKEN-2") ? "TOKEN-3" : "");
        List<ProductPage> pages = new ArrayList<>();
        int delivered = client.forEachProductPage(1L, pages::add);
        assertEquals(3, delivered);
        assertEquals(3, pages.size());
        assertEquals("POST /product/202502/products/search body={}", calls.get(0));
        assertEquals(Map.of("page_size", "2"), queries.get(0));
        assertEquals(Map.of("page_size", "2", "page_token", "TOKEN-2"), queries.get(1));
        assertEquals(Map.of("page_size", "2", "page_token", "TOKEN-3"), queries.get(2));
        queries.forEach(q -> assertFalse(q.containsKey("page") || q.containsKey("page_number") || q.containsKey("offset")));
    }

    @Test
    void aRepeatedPageTokenStopsTheWalk() {
        TiktokCatalogClient client = client(token -> "SAME");
        assertThrows(TiktokApiException.class, () -> client.forEachProductPage(1L, p -> { }));
        assertEquals(2, calls.size(), "second response repeats SAME, so the third request is never made");
    }

    @Test
    void maxPagesBoundsAMisbehavingServer() {
        TiktokCatalogClient client = client(token -> token + "x");
        assertThrows(TiktokApiException.class, () -> client.forEachProductPage(1L, p -> { }));
        assertEquals(5, calls.size());
    }

    @Test
    void aFailureOnALaterPageKeepsWhatEarlierPagesAlreadyDelivered() {
        failOnCall = 3;
        TiktokCatalogClient client = client(token -> token + "x");
        List<ProductPage> pages = new ArrayList<>();
        assertThrows(TiktokApiException.class, () -> client.forEachProductPage(1L, pages::add));
        assertEquals(2, pages.size());
    }

    @Test
    void productDetailRejectsIdsThatCouldChangeThePath() {
        TiktokCatalogClient client = client(token -> "");
        assertThrows(TiktokApiException.class, () -> client.getProduct(1L, "../orders"));
        assertThrows(TiktokApiException.class, () -> client.getProduct(1L, "1/inventory"));
        assertThrows(TiktokApiException.class, () -> client.getProduct(1L, null));
        assertEquals(0, calls.size());
        assertEquals("t", client.getProduct(1L, "1729").title());
        assertEquals("GET /product/202309/products/1729 body=null", calls.get(0));
    }

    @Test
    void warehousesAreListed() {
        assertEquals("W1", client(token -> "").listWarehouses(1L).get(0).id());
        assertEquals("GET /logistics/202309/warehouses body=null", calls.get(0));
    }

    private TiktokShopApiExecutor priceExecutor() {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        when(executor.call(eq(1L), eq("POST"), eq("/product/202309/products/P1/prices/update"), anyMap(), anyString()))
                .thenReturn(mapper.createObjectNode());
        return executor;
    }

    private JsonNode sentPrice(TiktokShopApiExecutor executor) throws Exception {
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(executor).call(eq(1L), eq("POST"), eq("/product/202309/products/P1/prices/update"), anyMap(), body.capture());
        return mapper.readTree(body.getValue()).path("skus").get(0);
    }

    @Test
    void priceWithZeroFractionIsSentAsIntegerVnd() throws Exception {
        TiktokShopApiExecutor executor = priceExecutor();
        new TiktokCatalogClient(executor, properties, mapper)
                .updatePrice(1L, "P1", "S1", new BigDecimal("200000"), new BigDecimal("159000.00"));

        JsonNode sku = sentPrice(executor);
        assertEquals("S1", sku.path("id").asText());
        assertEquals("159000", sku.path("price").path("amount").asText());
        assertEquals("VND", sku.path("price").path("currency").asText());
    }

    @Test
    void onlySalePriceIsEnoughAndOriginalPriceIsTheFallback() throws Exception {
        TiktokShopApiExecutor executor = priceExecutor();
        TiktokCatalogClient client = new TiktokCatalogClient(executor, properties, mapper);
        client.updatePrice(1L, "P1", "S1", null, new BigDecimal("1.59E+5"));
        assertEquals("159000", sentPrice(executor).path("price").path("amount").asText());

        assertEquals("120000", TiktokCatalogClient.priceAmount(new BigDecimal("120000.0"), null));
    }

    @Test
    void priceCurrencyComesFromTheLink() throws Exception {
        TiktokShopApiExecutor executor = priceExecutor();
        new TiktokCatalogClient(executor, properties, mapper)
                .updatePrice(1L, "P1", "S1", null, new BigDecimal("5"), "USD");
        assertEquals("USD", sentPrice(executor).path("price").path("currency").asText());
    }

    @Test
    void invalidPricesAreRejectedBeforeCallingTikTok() {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        TiktokCatalogClient client = new TiktokCatalogClient(executor, properties, mapper);

        for (BigDecimal bad : new BigDecimal[] { new BigDecimal("159000.50"), BigDecimal.ZERO, new BigDecimal("-1"), null }) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> client.updatePrice(1L, "P1", "S1", bad, null));
            assertEquals("TIKTOK_PRICE_INVALID", ex.getErrorCode());
            assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        }
        BusinessException badId = assertThrows(BusinessException.class,
                () -> client.updatePrice(1L, "../orders", "S1", BigDecimal.TEN, null));
        assertEquals("TIKTOK_PRODUCT_ID_INVALID", badId.getErrorCode());
        verifyNoInteractions(executor);
    }

    @Test
    void invalidStockInputIsAClientErrorWithAnExplicitCode() throws Exception {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        when(executor.call(eq(1L), eq("GET"), eq("/product/202309/products/P1"), anyMap(), isNull()))
                .thenReturn(mapper.readTree("{\"id\":\"P1\",\"skus\":[{\"id\":\"S1\",\"inventory\":[{\"warehouse_id\":\"W1\",\"quantity\":1}]},{\"id\":\"S2\",\"inventory\":[]}]}"));
        when(executor.call(eq(1L), eq("GET"), eq("/logistics/202309/warehouses"), anyMap(), isNull()))
                .thenReturn(mapper.readTree("{\"warehouses\":[{\"id\":\"W1\",\"is_default\":true}]}"));
        TiktokCatalogClient client = new TiktokCatalogClient(executor, properties, mapper);

        assertCode("TIKTOK_STOCK_INVALID", HttpStatus.BAD_REQUEST, () -> client.updateStock(1L, "P1", "S1", -1));
        assertCode("TIKTOK_STOCK_INVALID", HttpStatus.BAD_REQUEST, () -> client.updateStock(1L, "P1", "S1", null));
        assertCode("TIKTOK_STOCK_INVALID", HttpStatus.BAD_REQUEST, () -> client.updateStock(1L, "P1", "S1", 100_000));
        assertCode("TIKTOK_PRODUCT_ID_INVALID", HttpStatus.BAD_REQUEST, () -> client.updateStock(1L, "P/1", "S1", 1));
        assertCode("TIKTOK_SKU_NOT_ON_PRODUCT", HttpStatus.BAD_REQUEST, () -> client.updateStock(1L, "P1", "NOPE", 1));
        assertCode("TIKTOK_SKU_NO_WAREHOUSE", HttpStatus.CONFLICT, () -> client.updateStock(1L, "P1", "S2", 1));
        verify(executor, never()).call(anyLong(), eq("POST"), anyString(), anyMap(), anyString());
    }

    @Test
    void skuListedInDataErrorsIsNotTreatedAsSent() throws Exception {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        when(executor.call(eq(1L), eq("GET"), eq("/product/202309/products/P1"), anyMap(), isNull()))
                .thenReturn(mapper.readTree("{\"id\":\"P1\",\"skus\":[{\"id\":\"S1\",\"inventory\":[{\"warehouse_id\":\"W1\",\"quantity\":1}]}]}"));
        when(executor.call(eq(1L), eq("GET"), eq("/logistics/202309/warehouses"), anyMap(), isNull()))
                .thenReturn(mapper.readTree("{\"warehouses\":[{\"id\":\"W1\"}]}"));
        when(executor.call(eq(1L), eq("POST"), eq("/product/202309/products/P1/inventory/update"), anyMap(), anyString()))
                .thenReturn(mapper.readTree("{\"errors\":[{\"code\":12052700,\"detail\":{\"sku_id\":\"S1\"}}]}"));

        TiktokApiException ex = assertThrows(TiktokApiException.class,
                () -> new TiktokCatalogClient(executor, properties, mapper).updateStock(1L, "P1", "S1", 3));
        assertTrue(ex.isRejection());
        assertEquals(12052700, ex.getApiCode());
    }

    private static void assertCode(String code, HttpStatus status, org.junit.jupiter.api.function.Executable call) {
        BusinessException ex = assertThrows(BusinessException.class, call);
        assertEquals(code, ex.getErrorCode());
        assertEquals(status, ex.getHttpStatus());
    }

    @Test
    void stockUpdateUsesWarehouseInventoryEndpointAndSupportsZero() throws Exception {
        TiktokShopApiExecutor executor = mock(TiktokShopApiExecutor.class);
        when(executor.call(eq(1L), eq("GET"), eq("/product/202309/products/P1"), anyMap(), isNull()))
                .thenReturn(mapper.readTree("{\"id\":\"P1\",\"skus\":[{\"id\":\"S1\",\"inventory\":[{\"warehouse_id\":\"W1\",\"quantity\":40},{\"warehouse_id\":\"W2\",\"quantity\":25}]}]}"));
        when(executor.call(eq(1L), eq("GET"), eq("/logistics/202309/warehouses"), anyMap(), isNull()))
                .thenReturn(mapper.readTree("{\"warehouses\":[{\"id\":\"W1\",\"is_default\":true},{\"id\":\"W2\"}]}"));
        when(executor.call(eq(1L), eq("POST"), eq("/product/202309/products/P1/inventory/update"), anyMap(), anyString()))
                .thenReturn(mapper.readTree("{\"errors\":[]}"));
        TiktokCatalogClient client = new TiktokCatalogClient(executor, properties, mapper);

        client.updateStock(1L, "P1", "S1", 47);
        client.updateStock(1L, "P1", "S1", 0);

        var bodies = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).call(eq(1L), eq("POST"), eq("/product/202309/products/P1/inventory/update"), anyMap(), bodies.capture());
        assertEquals(22, mapper.readTree(bodies.getAllValues().get(0)).path("skus").get(0).path("inventory").get(0).path("quantity").asInt());
        assertEquals(25, mapper.readTree(bodies.getAllValues().get(0)).path("skus").get(0).path("inventory").get(1).path("quantity").asInt());
        assertEquals(0, mapper.readTree(bodies.getAllValues().get(1)).path("skus").get(0).path("inventory").get(0).path("quantity").asInt());
        assertEquals(0, mapper.readTree(bodies.getAllValues().get(1)).path("skus").get(0).path("inventory").get(1).path("quantity").asInt());
    }
}
