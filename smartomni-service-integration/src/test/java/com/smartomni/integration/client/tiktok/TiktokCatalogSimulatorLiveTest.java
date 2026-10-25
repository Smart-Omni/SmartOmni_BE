package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Warehouse;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real signing + HTTP + DTO mapping against a RUNNING simulator. Skipped unless
 * {@code TIKTOK_SIM_LIVE_URL}, {@code TIKTOK_SIM_APP_KEY}, {@code TIKTOK_SIM_APP_SECRET},
 * {@code TIKTOK_SIM_ACCESS_TOKEN} and {@code TIKTOK_SIM_SHOP_CIPHER} are set. Only the base URL differs from
 * production; there is no simulator branch in the client.
 */
@EnabledIfEnvironmentVariable(named = "TIKTOK_SIM_LIVE_URL", matches = ".+")
class TiktokCatalogSimulatorLiveTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private TiktokCatalogClient client(int pageSize) {
        TiktokProperties properties = new TiktokProperties();
        properties.setAppKey(System.getenv("TIKTOK_SIM_APP_KEY"));
        properties.setAppSecret(System.getenv("TIKTOK_SIM_APP_SECRET"));
        properties.setOpenApiBaseUrl(System.getenv("TIKTOK_SIM_LIVE_URL"));
        properties.setProductPageSize(pageSize);
        properties.setMaxPages(100);
        TiktokOpenApiClient open = new TiktokOpenApiClient(properties, new WebClientTiktokHttpTransport(properties),
                Clock.systemUTC(), mapper);
        TiktokShopApiExecutor executor = new TiktokShopApiExecutor(null, null, null) {
            @Override
            public JsonNode call(Long connectionId, String method, String path, Map<String, String> query, String body) {
                return open.call(method, path, query, body, System.getenv("TIKTOK_SIM_ACCESS_TOKEN"),
                        System.getenv("TIKTOK_SIM_SHOP_CIPHER"));
            }
        };
        return new TiktokCatalogClient(executor, properties, mapper);
    }

    @Test
    void pagesEveryProductOnceAndReadsDetailAndWarehouses() {
        TiktokCatalogClient client = client(20);
        Set<String> ids = new HashSet<>();
        int[] total = {0};
        int pages = client.forEachProductPage(1L, page -> page.products().forEach(p -> {
            total[0]++;
            ids.add(p.id());
            assertNotNull(p.skus());
            p.skus().forEach(s -> assertTrue(s.price().salePrice().matches("\\d+"), "sale_price is a numeric string"));
        }));
        assertTrue(pages >= 2, "more than one page, got " + pages);
        assertEquals(total[0], ids.size(), "no duplicates across pages");

        Product tee = client.getProduct(1L, "sim-product-a-tee");
        assertEquals(3, tee.skus().size());
        assertEquals(List.of("129000", "129000", "135000"),
                tee.skus().stream().map(s -> s.price().salePrice()).sorted().toList());

        List<Warehouse> warehouses = client.listWarehouses(1L);
        Set<String> warehouseIds = new HashSet<>();
        warehouses.forEach(w -> warehouseIds.add(w.id()));
        tee.skus().forEach(s -> s.inventory().forEach(i -> assertTrue(warehouseIds.contains(i.warehouseId()))));
    }
}
