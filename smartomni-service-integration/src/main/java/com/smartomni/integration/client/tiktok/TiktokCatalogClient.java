package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Warehouse;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.WarehouseList;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Catalog calls for one connection, routed through {@link TiktokShopApiExecutor} (token, shop cipher, signing,
 * one refresh-and-retry). Pagination uses the platform's opaque {@code page_token}; there is no page index.
 * Operations (all {@code unverified} in the contracts manifest): {@code POST /product/202502/products/search},
 * {@code GET /product/202309/products/{product_id}}, {@code GET /logistics/202309/warehouses}.
 */
@Component
public class TiktokCatalogClient {

    static final String SEARCH_PATH = "/product/202502/products/search";
    static final String DETAIL_PATH = "/product/202309/products/";
    static final String WAREHOUSES_PATH = "/logistics/202309/warehouses";

    private final TiktokShopApiExecutor executor;
    private final TiktokProperties properties;
    private final ObjectMapper mapper;

    public TiktokCatalogClient(TiktokShopApiExecutor executor, TiktokProperties properties, ObjectMapper mapper) {
        this.executor = executor;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** One page of Search Products. {@code pageToken} is "" or null for the first page. */
    public ProductPage searchProducts(Long connectionId, String pageToken) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page_size", Integer.toString(properties.getProductPageSize()));
        if (pageToken != null && !pageToken.isEmpty()) {
            query.put("page_token", pageToken);
        }
        return convert(executor.call(connectionId, "POST", SEARCH_PATH, query, "{}"), ProductPage.class);
    }

    /**
     * Walks every page, handing each to {@code consumer} before the next request, so a failure on page N
     * keeps what pages 1..N-1 already delivered. Stops with an error on a repeated token or after max-pages.
     *
     * @return number of pages delivered
     */
    public int forEachProductPage(Long connectionId, Consumer<ProductPage> consumer) {
        Set<String> seen = new HashSet<>();
        String token = "";
        for (int page = 1; page <= properties.getMaxPages(); page++) {
            ProductPage result = searchProducts(connectionId, token);
            consumer.accept(result);
            if (result.nextPageToken().isEmpty()) {
                return page;
            }
            if (!seen.add(result.nextPageToken())) {
                throw new TiktokApiException("TikTok pagination repeated a page token", 200, null, null, false);
            }
            token = result.nextPageToken();
        }
        throw new TiktokApiException("TikTok pagination exceeded " + properties.getMaxPages() + " pages", 200, null, null, false);
    }

    /** Get Product (detail); description, images and sales attributes are not in the search response. */
    public Product getProduct(Long connectionId, String productId) {
        if (productId == null || !productId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new TiktokApiException("Refusing to request a product with an unexpected id format", 0, null, null, false);
        }
        return convert(executor.call(connectionId, "GET", DETAIL_PATH + productId, Map.of(), null), Product.class);
    }

    public List<Warehouse> listWarehouses(Long connectionId) {
        return convert(executor.call(connectionId, "GET", WAREHOUSES_PATH, Map.of(), null), WarehouseList.class).warehouses();
    }

    /**
     * Updates product stock on TikTok Shop (POST /product/202309/products/{product_id}/stocks/update).
     */
    public void updateStock(Long connectionId, String productId, String skuId, Integer quantity) {
        String path = "/product/202309/products/" + productId + "/stocks/update";
        ObjectNode body = mapper.createObjectNode();
        ArrayNode skus = body.putArray("skus");
        ObjectNode skuEntry = skus.addObject();
        skuEntry.put("id", skuId);
        ArrayNode stockInfos = skuEntry.putArray("stock_infos");
        ObjectNode stockInfo = stockInfos.addObject();
        stockInfo.put("available_stock", quantity != null ? quantity : 0);

        executor.call(connectionId, "POST", path, Map.of(), body.toString());
    }

    public void updateStock(String shopId, String productId, String skuId, Integer quantity) {
        String path = "/product/202309/products/" + productId + "/stocks/update";
        ObjectNode body = mapper.createObjectNode();
        ArrayNode skus = body.putArray("skus");
        ObjectNode skuEntry = skus.addObject();
        skuEntry.put("id", skuId);
        ArrayNode stockInfos = skuEntry.putArray("stock_infos");
        ObjectNode stockInfo = stockInfos.addObject();
        stockInfo.put("available_stock", quantity != null ? quantity : 0);

        executor.callByShopCipher(shopId, "POST", path, Map.of(), body.toString());
    }

    /**
     * Updates product price on TikTok Shop (POST /product/202309/products/{product_id}/prices/update).
     */
    public void updatePrice(Long connectionId, String productId, String skuId, BigDecimal originalPrice, BigDecimal salePrice) {
        String path = "/product/202309/products/" + productId + "/prices/update";
        ObjectNode body = mapper.createObjectNode();
        ArrayNode skus = body.putArray("skus");
        ObjectNode skuEntry = skus.addObject();
        skuEntry.put("id", skuId);
        ObjectNode priceObj = skuEntry.putObject("price");
        BigDecimal targetPrice = originalPrice != null ? originalPrice : (salePrice != null ? salePrice : BigDecimal.ZERO);
        priceObj.put("amount", targetPrice.toPlainString());
        priceObj.put("currency", "VND");

        executor.call(connectionId, "POST", path, Map.of(), body.toString());
    }

    public void updatePrice(String shopId, String productId, String skuId, BigDecimal originalPrice, BigDecimal salePrice) {
        String path = "/product/202309/products/" + productId + "/prices/update";
        ObjectNode body = mapper.createObjectNode();
        ArrayNode skus = body.putArray("skus");
        ObjectNode skuEntry = skus.addObject();
        skuEntry.put("id", skuId);
        ObjectNode priceObj = skuEntry.putObject("price");
        BigDecimal targetPrice = originalPrice != null ? originalPrice : (salePrice != null ? salePrice : BigDecimal.ZERO);
        priceObj.put("amount", targetPrice.toPlainString());
        priceObj.put("currency", "VND");

        executor.callByShopCipher(shopId, "POST", path, Map.of(), body.toString());
    }

    private <T> T convert(JsonNode data, Class<T> type) {
        try {
            JsonNode source = data == null || data.isMissingNode() || data.isNull() ? mapper.createObjectNode() : data;
            return mapper.treeToValue(source, type);
        } catch (JsonProcessingException e) {
            // Not echoing the payload: it may contain shop data.
            throw new TiktokApiException("TikTok response did not match the expected shape for " + type.getSimpleName(), 200, null, null, false);
        }
    }
}
