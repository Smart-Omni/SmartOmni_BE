package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.MarketplaceProductDto;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.WarehouseList;
import com.smartomni.integration.service.catalog.TiktokProductMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TiktokCatalogDtosTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void amountsAreParsedSafelyAndNeverFallBackToZero() {
        assertEquals(new BigDecimal("129000"), TiktokCatalogDtos.parseAmount("129000"));
        assertEquals(new BigDecimal("129000.50"), TiktokCatalogDtos.parseAmount(" 129000.50 "));
        assertNull(TiktokCatalogDtos.parseAmount(null));
        assertNull(TiktokCatalogDtos.parseAmount(""));
        assertNull(TiktokCatalogDtos.parseAmount("  "));
        assertNull(TiktokCatalogDtos.parseAmount("TEST_amount"));
        assertNull(TiktokCatalogDtos.parseAmount("-5"));
        assertEquals(0, TiktokCatalogDtos.parseAmount("0").signum(), "zero parses as zero; rejecting it is the price policy job");
    }

    @Test
    void searchPageKeepsPlatformIdsSellerSkuCurrencyAndWarehouseStock() throws Exception {
        String json = "{\"next_page_token\":\"T2\",\"total_count\":3,\"products\":[{\"id\":\"1729\",\"title\":\"Ao\",\"status\":\"ACTIVATE\","
                + "\"unknown_future_field\":{\"x\":1},\"skus\":[{\"id\":\"9001\",\"seller_sku\":\"TEE-M\",\"price\":{\"currency\":\"VND\",\"sale_price\":\"129000\"},"
                + "\"inventory\":[{\"warehouse_id\":\"W1\",\"quantity\":40},{\"warehouse_id\":\"W2\",\"quantity\":25}]}]}]}";
        ProductPage page = mapper.treeToValue(mapper.readTree(json), ProductPage.class);
        assertEquals("T2", page.nextPageToken());
        assertEquals(3, page.totalCount());
        var sku = page.products().get(0).skus().get(0);
        assertEquals("9001", sku.id());
        assertEquals("TEE-M", sku.sellerSku());
        assertEquals("VND", sku.price().currency());
        assertEquals("W2", sku.inventory().get(1).warehouseId());
        assertEquals(25, sku.inventory().get(1).quantity());
    }

    @Test
    void emptyPageAndMissingArraysAreEmptyNotNull() throws Exception {
        ProductPage page = mapper.treeToValue(mapper.readTree("{}"), ProductPage.class);
        assertEquals("", page.nextPageToken());
        assertTrue(page.products().isEmpty());
        Product product = mapper.treeToValue(mapper.readTree("{\"id\":\"1\"}"), Product.class);
        assertTrue(product.skus().isEmpty());
    }

    @Test
    void mapperBuildsNeutralProductWithVariantNamesFromSalesAttributes() throws Exception {
        String json = "{\"id\":\"1729\",\"title\":\"Ao thun\",\"description\":\"d\",\"status\":\"ACTIVATE\",\"main_images\":[{\"urls\":[\"http://img/1.jpg\"]}],"
                + "\"skus\":[{\"id\":\"9001\",\"seller_sku\":\" TEE-WHT-M \",\"sales_attributes\":[{\"name\":\"Color\",\"value_name\":\"Trang\"},{\"name\":\"Size\",\"value_name\":\"M\"}],"
                + "\"price\":{\"currency\":\"VND\",\"sale_price\":\"129000\"}},{\"id\":\"9002\",\"seller_sku\":\"\",\"price\":{\"currency\":\"VND\",\"sale_price\":\"oops\"}}]}";
        MarketplaceProductDto dto = TiktokProductMapper.toNeutral(mapper.treeToValue(mapper.readTree(json), Product.class), "X");
        assertEquals("1729", dto.getPlatformItemId());
        assertEquals("ACTIVATE", dto.getPlatformStatus());
        assertEquals(java.util.List.of("http://img/1.jpg"), dto.getImageUrls());
        var first = dto.getVariants().get(0);
        assertEquals("9001", first.getExternalSkuId());
        assertEquals("TEE-WHT-M", first.getSellerSku(), "seller SKU is trimmed");
        assertEquals("Trang / M", first.getVariantName());
        assertEquals(new BigDecimal("129000"), first.getPrice());
        var second = dto.getVariants().get(1);
        assertNull(second.getSellerSku(), "blank seller SKU stays null so it is reported, not guessed");
        assertNull(second.getPrice(), "an unparsable amount is null, never 0");
    }

    @Test
    void warehouseListParses() throws Exception {
        WarehouseList list = mapper.treeToValue(mapper.readTree("{\"warehouses\":[{\"id\":\"W1\",\"name\":\"Kho HN\",\"is_default\":true,"
                + "\"effect_status\":\"ENABLED\",\"address\":{\"city\":\"Ha Noi\",\"region_code\":\"VN\"}}]}"), WarehouseList.class);
        assertEquals("W1", list.warehouses().get(0).id());
        assertTrue(list.warehouses().get(0).isDefault());
        assertEquals("Ha Noi", list.warehouses().get(0).address().city());
    }

    /** The official-OAS fixtures use TEST_ placeholders (amounts are not numeric); the point is that the contract shape deserialises. */
    @Test
    void contractFixturesDeserialiseIntoTheTypedDtos() throws Exception {
        Path root = Path.of("src", "test", "resources", "tiktok-contract", "fixtures");
        assertTrue(Files.exists(root.resolve("product.search.json")), "vendored contract fixtures are missing");
        for (String op : new String[]{"product.search", "product.detail", "logistics.warehouses"}) {
            JsonNode fixture = mapper.readTree(Files.readString(root.resolve(op + ".json")));
            JsonNode success = null;
            for (JsonNode c : fixture.get("cases")) {
                if ("success".equals(c.path("case").asText())) {
                    success = c.get("response").get("data");
                }
            }
            assertNotNull(success, op);
            switch (op) {
                case "product.search" -> {
                    ProductPage page = mapper.treeToValue(success, ProductPage.class);
                    assertFalse(page.products().isEmpty());
                    assertEquals("TEST_seller_sku", page.products().get(0).skus().get(0).sellerSku());
                    assertEquals(999, page.products().get(0).skus().get(0).inventory().get(0).quantity());
                }
                case "product.detail" -> assertFalse(mapper.treeToValue(success, Product.class).skus().isEmpty());
                default -> assertFalse(mapper.treeToValue(success, WarehouseList.class).warehouses().isEmpty());
            }
        }
    }
}
