package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.ProductPage;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Warehouse;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.WarehouseList;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import org.springframework.http.HttpStatus;
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
    static final int MAX_WAREHOUSE_STOCK = 99_999;

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
     * Updates SKU stock on TikTok Shop (POST /product/202309/products/{product_id}/inventory/update). The body
     * carries the per-warehouse quantities that make the SKU total equal {@code quantity}.
     *
     * @throws BusinessException 400 {@code TIKTOK_STOCK_INVALID}, {@code TIKTOK_PRODUCT_ID_INVALID} or
     *         {@code TIKTOK_SKU_NOT_ON_PRODUCT}, 409 {@code TIKTOK_SKU_NO_WAREHOUSE}, for input TikTok cannot accept
     * @throws TiktokApiException when the platform fails or rejects the SKU (including an entry in {@code data.errors})
     */
    public void updateStock(Long connectionId, String productId, String skuId, Integer quantity) {
        requireStock(quantity);
        String path = inventoryPath(productId);
        Product product = getProduct(connectionId, productId);
        List<Warehouse> warehouses = listWarehouses(connectionId);
        JsonNode result = executor.call(connectionId, "POST", path, Map.of(),
                stockBody(product, warehouses, skuId, quantity));
        verifyStockResult(result, skuId);
    }

    public void updateStock(String shopId, String productId, String skuId, Integer quantity) {
        requireStock(quantity);
        String path = inventoryPath(productId);
        Product product = convert(executor.callByShopCipher(shopId, "GET", DETAIL_PATH + productId, Map.of(), null), Product.class);
        WarehouseList list = convert(executor.callByShopCipher(shopId, "GET", WAREHOUSES_PATH, Map.of(), null), WarehouseList.class);
        JsonNode result = executor.callByShopCipher(shopId, "POST", path, Map.of(),
                stockBody(product, list.warehouses(), skuId, quantity));
        verifyStockResult(result, skuId);
    }

    private static void requireStock(Integer quantity) {
        // The 99,999 cap is per warehouse (checked in stockBody), so a larger SKU total can still be valid.
        if (quantity == null || quantity < 0) {
            throw new BusinessException("Stock must be a non-negative integer", HttpStatus.BAD_REQUEST, "TIKTOK_STOCK_INVALID");
        }
    }

    private static String requireProductId(String productId) {
        if (productId == null || !productId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException("Invalid TikTok product id", HttpStatus.BAD_REQUEST, "TIKTOK_PRODUCT_ID_INVALID");
        }
        return productId;
    }

    private static String inventoryPath(String productId) {
        return DETAIL_PATH + requireProductId(productId) + "/inventory/update";
    }

    private static String pricePath(String productId) {
        return DETAIL_PATH + requireProductId(productId) + "/prices/update";
    }

    private String stockBody(Product product, List<Warehouse> warehouses, String skuId, Integer quantity) {
        Sku sku = product.skus().stream().filter(s -> skuId != null && skuId.equals(s.id())).findFirst()
                .orElseThrow(() -> new BusinessException("TikTok SKU is not on the product",
                        HttpStatus.BAD_REQUEST, "TIKTOK_SKU_NOT_ON_PRODUCT"));
        if (sku.inventory().isEmpty()) {
            throw new BusinessException("TikTok SKU has no assigned warehouse", HttpStatus.CONFLICT, "TIKTOK_SKU_NO_WAREHOUSE");
        }
        var levels = new java.util.ArrayList<>(sku.inventory());
        levels.sort(java.util.Comparator.comparing(TiktokCatalogDtos.Inventory::warehouseId));
        String defaultWarehouse = warehouses.stream().filter(w -> Boolean.TRUE.equals(w.isDefault()))
                .map(Warehouse::id).filter(id -> levels.stream().anyMatch(i -> id.equals(i.warehouseId())))
                .findFirst().orElse(levels.get(0).warehouseId());
        levels.sort(java.util.Comparator.comparing(i -> !defaultWarehouse.equals(i.warehouseId())));
        long current = 0;
        for (var level : levels) {
            if (level.quantity() == null || level.quantity() < 0) {
                // The platform's own product data is incomplete: not a client input error.
                throw new TiktokApiException("TikTok warehouse stock is missing", 200, null, null, false);
            }
            current += level.quantity();
        }
        long delta = quantity - current;
        ObjectNode body = mapper.createObjectNode();
        ObjectNode entry = body.putArray("skus").addObject();
        entry.put("id", skuId);
        ArrayNode inventory = entry.putArray("inventory");
        for (var level : levels) {
            long next = level.quantity();
            if (delta < 0) {
                long decrease = Math.min(next, -delta);
                next -= decrease;
                delta += decrease;
            } else if (delta > 0 && defaultWarehouse.equals(level.warehouseId())) {
                next += delta;
                delta = 0;
            }
            if (next > MAX_WAREHOUSE_STOCK) {
                throw new BusinessException("TikTok warehouse stock exceeds " + MAX_WAREHOUSE_STOCK,
                        HttpStatus.BAD_REQUEST, "TIKTOK_STOCK_INVALID");
            }
            ObjectNode warehouse = inventory.addObject();
            warehouse.put("warehouse_id", level.warehouseId());
            warehouse.put("quantity", next);
        }
        return body.toString();
    }

    private void verifyStockResult(JsonNode result, String skuId) {
        for (JsonNode error : result.path("errors")) {
            if (skuId.equals(error.path("detail").path("sku_id").asText())) {
                throw new TiktokApiException("TikTok rejected SKU inventory update", 200,
                        error.path("code").asInt(), null, false);
            }
        }
    }

    /**
     * Updates SKU price on TikTok Shop (POST /product/202309/products/{product_id}/prices/update). The amount sent
     * is {@code salePrice}, falling back to {@code originalPrice} (the import reads {@code sale_price}), as an
     * integer string because VND has no minor units ({@code 159000.00} becomes {@code "159000"}).
     *
     * @param currency currency of the SKU link; {@code null} or blank means VND
     * @throws BusinessException 400 {@code TIKTOK_PRICE_INVALID} when the price is missing, not positive or has a
     *         non-zero fraction; 400 {@code TIKTOK_PRODUCT_ID_INVALID} for a malformed product id
     */
    public void updatePrice(Long connectionId, String productId, String skuId, BigDecimal originalPrice,
                            BigDecimal salePrice, String currency) {
        String path = pricePath(productId);
        executor.call(connectionId, "POST", path, Map.of(), priceBody(skuId, originalPrice, salePrice, currency));
    }

    public void updatePrice(Long connectionId, String productId, String skuId, BigDecimal originalPrice, BigDecimal salePrice) {
        updatePrice(connectionId, productId, skuId, originalPrice, salePrice, null);
    }

    public void updatePrice(String shopId, String productId, String skuId, BigDecimal originalPrice, BigDecimal salePrice) {
        String path = pricePath(productId);
        executor.callByShopCipher(shopId, "POST", path, Map.of(), priceBody(skuId, originalPrice, salePrice, null));
    }

    /** The integer amount TikTok expects for a VND price, e.g. {@code 159000.00 -> "159000"}. */
    public static String priceAmount(BigDecimal originalPrice, BigDecimal salePrice) {
        BigDecimal price = salePrice != null ? salePrice : originalPrice;
        if (price == null || price.signum() <= 0) {
            throw new BusinessException("TikTok price must be a positive amount", HttpStatus.BAD_REQUEST, "TIKTOK_PRICE_INVALID");
        }
        BigDecimal normalized = price.stripTrailingZeros();
        if (normalized.scale() > 0) {
            throw new BusinessException("TikTok VND price must be a whole number", HttpStatus.BAD_REQUEST, "TIKTOK_PRICE_INVALID");
        }
        return normalized.setScale(0).toPlainString();
    }

    private String priceBody(String skuId, BigDecimal originalPrice, BigDecimal salePrice, String currency) {
        String amount = priceAmount(originalPrice, salePrice);
        ObjectNode body = mapper.createObjectNode();
        ObjectNode skuEntry = body.putArray("skus").addObject();
        skuEntry.put("id", skuId);
        ObjectNode priceObj = skuEntry.putObject("price");
        priceObj.put("amount", amount);
        priceObj.put("currency", currency == null || currency.isBlank() ? "VND" : currency);
        return body.toString();
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
