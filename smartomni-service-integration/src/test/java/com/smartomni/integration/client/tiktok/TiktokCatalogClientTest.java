package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
}
