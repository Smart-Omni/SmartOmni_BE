package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.config.ShopeeProperties;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import com.smartomni.integration.service.shopee.ShopeeTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopeeCatalogClientTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private ShopeeProperties properties;
    private ShopeeTokenService tokenService;
    private FakeShopeeTransport transport;
    private ShopeeCatalogClient client;

    @BeforeEach
    void setUp() {
        properties = new ShopeeProperties();
        properties.setPartnerId(1001141L);
        properties.setPartnerKey("shopee_mock_secret_key_2026");
        properties.setBaseUrl("http://shopee-simulator.test:8080");

        tokenService = mock(ShopeeTokenService.class);
        when(tokenService.credentials(anyLong()))
                .thenReturn(new ShopeeTokenService.Credentials("AT-valid-123", 727720655L, 1L));

        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        transport = new FakeShopeeTransport(s -> {
            String path = s.uri().getPath();
            if (path.contains("/product/get_item_list")) {
                return FakeShopeeTransport.ok("{\"item\":[{\"item_id\":1001,\"item_status\":\"NORMAL\"}],\"total_count\":1,\"has_next_page\":false,\"next_offset\":1}");
            }
            if (path.contains("/product/get_item_base_info")) {
                return FakeShopeeTransport.ok("{\"item_list\":[{\"item_id\":1001,\"item_name\":\"Smart T-Shirt\",\"item_sku\":\"TS-01\",\"description\":\"100% Cotton\",\"has_model\":true,\"image\":{\"image_url_list\":[\"http://img.png\"]}}]}");
            }
            if (path.contains("/product/get_model_list")) {
                return FakeShopeeTransport.ok("{\"model\":[{\"model_id\":9001,\"model_name\":\"Black / M\",\"model_sku\":\"TS-01-BLK-M\",\"price_info\":[{\"currency\":\"VND\",\"original_price\":250000,\"current_price\":250000}],\"stock_info_v2\":{\"summary_info\":{\"total_available_stock\":50}}}]}");
            }
            if (path.contains("/shop/get_warehouse_detail")) {
                return FakeShopeeTransport.ok("{\"warehouse_list\":[{\"warehouse_id\":\"WH-101\",\"warehouse_name\":\"Hanoi Main WH\",\"address\":\"123 Street\",\"city\":\"Hanoi\",\"region\":\"VN\"}]}");
            }
            if (path.contains("/product/update_stock")) {
                return FakeShopeeTransport.ok("{\"success_list\":[{\"model_id\":9001,\"stock\":45}],\"failure_list\":[]}");
            }
            if (path.contains("/product/update_price")) {
                return FakeShopeeTransport.ok("{\"success_list\":[{\"model_id\":9001,\"original_price\":260000}],\"failure_list\":[]}");
            }
            return FakeShopeeTransport.ok("{}");
        });

        ShopeeShopApiExecutor executor = new ShopeeShopApiExecutor(tokenService, properties, transport, clock, mapper);
        client = new ShopeeCatalogClient(executor, mapper);
    }

    @Test
    void getItemListSendsShopSignedQueryAndParsesPagination() {
        ShopeeCatalogClient.ItemListResult res = client.getItemList(88L, 0, 50, "NORMAL");

        assertEquals(1, res.itemIds().size());
        assertEquals(1001L, res.itemIds().get(0));
        assertEquals(1, res.totalCount());
        assertFalse(res.hasNextPage());

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/product/get_item_list", sent.uri().getPath());
        String q = URLDecoder.decode(sent.uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(q.contains("shop_id=727720655"));
        assertTrue(q.contains("access_token=AT-valid-123"));
        assertTrue(q.contains("sign="));
        assertTrue(q.contains("item_status=NORMAL"));
    }

    @Test
    void getItemBaseInfoSendsBatchIdsAndParsesDetails() {
        List<ShopeeCatalogClient.ShopeeItemDetail> items = client.getItemBaseInfo(88L, List.of(1001L));

        assertEquals(1, items.size());
        ShopeeCatalogClient.ShopeeItemDetail item = items.get(0);
        assertEquals(1001L, item.itemId());
        assertEquals("Smart T-Shirt", item.itemName());
        assertEquals("TS-01", item.itemSku());
        assertTrue(item.hasModel());
        assertEquals("http://img.png", item.imageUrl());
    }

    @Test
    void getModelListParsesVariationModels() {
        List<ShopeeCatalogClient.ShopeeModelDetail> models = client.getModelList(88L, 1001L);

        assertEquals(1, models.size());
        ShopeeCatalogClient.ShopeeModelDetail model = models.get(0);
        assertEquals(9001L, model.modelId());
        assertEquals("Black / M", model.modelName());
        assertEquals("TS-01-BLK-M", model.modelSku());
        assertEquals(new BigDecimal("250000.0"), model.currentPrice());
        assertEquals(50, model.availableStock());
    }

    @Test
    void listWarehousesParsesWarehouseAddresses() {
        List<ShopeeCatalogClient.ShopeeWarehouseDetail> warehouses = client.listWarehouses(88L);

        assertEquals(1, warehouses.size());
        assertEquals("WH-101", warehouses.get(0).warehouseId());
        assertEquals("Hanoi Main WH", warehouses.get(0).name());
        assertEquals("Hanoi", warehouses.get(0).city());
    }

    @Test
    void updateStockSendsPayloadWithModelIdAndLocation() {
        client.updateStock(88L, 1001L, 9001L, 45);

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/product/update_stock", sent.uri().getPath());
        assertTrue(sent.body().contains("\"item_id\":1001"));
        assertTrue(sent.body().contains("\"model_id\":9001"));
        assertTrue(sent.body().contains("\"stock\":45"));
    }

    @Test
    void updatePriceSendsPayloadWithModelIdAndPrice() {
        client.updatePrice(88L, 1001L, 9001L, new BigDecimal("260000"));

        FakeShopeeTransport.Sent sent = transport.sent.get(0);
        assertEquals("/api/v2/product/update_price", sent.uri().getPath());
        assertTrue(sent.body().contains("\"item_id\":1001"));
        assertTrue(sent.body().contains("\"model_id\":9001"));
        assertTrue(sent.body().contains("260000"));
    }
}
