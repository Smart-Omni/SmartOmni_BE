package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Client for Shopee Product and Catalog APIs routed through {@link ShopeeShopApiExecutor}.
 */
@Component
public class ShopeeCatalogClient {

    private final ShopeeShopApiExecutor executor;
    private final ObjectMapper mapper;

    public ShopeeCatalogClient(ShopeeShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public record ItemListResult(List<Long> itemIds, int totalCount, boolean hasNextPage, int nextOffset) {
    }

    public record ShopeeItemDetail(long itemId, String itemName, String itemSku, String description,
                                   String itemStatus, boolean hasModel, String currency,
                                   BigDecimal originalPrice, BigDecimal currentPrice, int availableStock,
                                   String imageUrl) {
    }

    public record ShopeeModelDetail(long modelId, String modelName, String modelSku, String currency,
                                    BigDecimal originalPrice, BigDecimal currentPrice, int availableStock) {
    }

    public record ShopeeWarehouseDetail(String warehouseId, String name, String address, String city, String region) {
    }

    /** Retrieves paginated item IDs from Shopee (GET /api/v2/product/get_item_list). */
    public ItemListResult getItemList(Long connectionId, int offset, int pageSize, String itemStatus) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("offset", String.valueOf(offset));
        query.put("page_size", String.valueOf(Math.min(pageSize, 50)));
        if (itemStatus != null && !itemStatus.isBlank()) {
            query.put("item_status", itemStatus);
        }

        JsonNode data = executor.call(connectionId, "GET", "/api/v2/product/get_item_list", query, null);
        List<Long> itemIds = new ArrayList<>();
        JsonNode itemsArr = data.path("item");
        if (itemsArr.isArray()) {
            for (JsonNode it : itemsArr) {
                long id = it.path("item_id").asLong();
                if (id > 0) itemIds.add(id);
            }
        }
        int totalCount = data.path("total_count").asInt(0);
        boolean hasNextPage = data.path("has_next_page").asBoolean(false);
        int nextOffset = data.path("next_offset").asInt(offset + itemIds.size());

        return new ItemListResult(itemIds, totalCount, hasNextPage, nextOffset);
    }

    /** Batch fetches base information for up to 50 items (GET /api/v2/product/get_item_base_info). */
    public List<ShopeeItemDetail> getItemBaseInfo(Long connectionId, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        List<String> idStrs = itemIds.stream().map(String::valueOf).toList();
        Map<String, String> query = Map.of("item_id_list", String.join(",", idStrs));

        JsonNode data = executor.call(connectionId, "GET", "/api/v2/product/get_item_base_info", query, null);
        List<ShopeeItemDetail> results = new ArrayList<>();
        JsonNode list = data.path("item_list");
        if (list.isArray()) {
            for (JsonNode item : list) {
                long itemId = item.path("item_id").asLong();
                String name = item.path("item_name").asText("");
                String sku = item.path("item_sku").asText("");
                String desc = item.path("description").asText("");
                String status = item.path("item_status").asText("NORMAL");
                boolean hasModel = item.path("has_model").asBoolean(false);

                String currency = "VND";
                BigDecimal originalPrice = BigDecimal.ZERO;
                BigDecimal currentPrice = BigDecimal.ZERO;
                JsonNode priceInfo = item.path("price_info");
                if (priceInfo.isArray() && !priceInfo.isEmpty()) {
                    JsonNode firstPrice = priceInfo.get(0);
                    currency = firstPrice.path("currency").asText("VND");
                    originalPrice = BigDecimal.valueOf(firstPrice.path("original_price").asDouble(0));
                    currentPrice = BigDecimal.valueOf(firstPrice.path("current_price").asDouble(0));
                }

                int availableStock = item.path("stock_info_v2").path("summary_info").path("total_available_stock").asInt(0);
                String imageUrl = null;
                JsonNode imgArr = item.path("image").path("image_url_list");
                if (imgArr.isArray() && !imgArr.isEmpty()) {
                    imageUrl = imgArr.get(0).asText();
                }

                results.add(new ShopeeItemDetail(itemId, name, sku, desc, status, hasModel, currency,
                        originalPrice, currentPrice, availableStock, imageUrl));
            }
        }
        return results;
    }

    /** Fetches 2-tier variation models for an item (GET /api/v2/product/get_model_list). */
    public List<ShopeeModelDetail> getModelList(Long connectionId, Long itemId) {
        Map<String, String> query = Map.of("item_id", String.valueOf(itemId));
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/product/get_model_list", query, null);

        List<ShopeeModelDetail> models = new ArrayList<>();
        JsonNode modelArr = data.path("model");
        if (modelArr.isArray()) {
            for (JsonNode m : modelArr) {
                long modelId = m.path("model_id").asLong();
                String modelName = m.path("model_name").asText("");
                String modelSku = m.path("model_sku").asText("");

                String currency = "VND";
                BigDecimal originalPrice = BigDecimal.ZERO;
                BigDecimal currentPrice = BigDecimal.ZERO;
                JsonNode priceInfo = m.path("price_info");
                if (priceInfo.isArray() && !priceInfo.isEmpty()) {
                    JsonNode firstPrice = priceInfo.get(0);
                    currency = firstPrice.path("currency").asText("VND");
                    originalPrice = BigDecimal.valueOf(firstPrice.path("original_price").asDouble(0));
                    currentPrice = BigDecimal.valueOf(firstPrice.path("current_price").asDouble(0));
                }

                int availableStock = m.path("stock_info_v2").path("summary_info").path("total_available_stock").asInt(0);
                models.add(new ShopeeModelDetail(modelId, modelName, modelSku, currency, originalPrice, currentPrice, availableStock));
            }
        }
        return models;
    }

    /** Fetches shop warehouses (GET /api/v2/shop/get_warehouse_detail). */
    public List<ShopeeWarehouseDetail> listWarehouses(Long connectionId) {
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/shop/get_warehouse_detail", Map.of(), null);
        List<ShopeeWarehouseDetail> warehouses = new ArrayList<>();
        JsonNode list = data.path("warehouse_list");
        if (list.isArray()) {
            for (JsonNode w : list) {
                String id = w.path("warehouse_id").asText();
                String name = w.path("warehouse_name").asText("");
                String addr = w.path("address").asText("");
                String city = w.path("city").asText("");
                String region = w.path("region").asText("VN");
                warehouses.add(new ShopeeWarehouseDetail(id, name, addr, city, region));
            }
        }
        return warehouses;
    }

    /** Updates stock on Shopee (POST /api/v2/product/update_stock). */
    public void updateStock(Long connectionId, Long itemId, Long modelId, int stock) {
        ObjectNode body = mapper.createObjectNode();
        body.put("item_id", itemId);

        ArrayNode stockList = body.putArray("stock_list");
        ObjectNode entry = stockList.addObject();
        entry.put("model_id", modelId != null ? modelId : 0L);

        ArrayNode sellerStock = entry.putArray("seller_stock");
        ObjectNode stockEntry = sellerStock.addObject();
        stockEntry.put("location_id", "VN_LOCAL");
        stockEntry.put("stock", stock);

        executor.call(connectionId, "POST", "/api/v2/product/update_stock", Map.of(), body.toString());
    }

    /** Updates price on Shopee (POST /api/v2/product/update_price). */
    public void updatePrice(Long connectionId, Long itemId, Long modelId, BigDecimal price) {
        ObjectNode body = mapper.createObjectNode();
        body.put("item_id", itemId);

        ArrayNode priceList = body.putArray("price_list");
        ObjectNode entry = priceList.addObject();
        entry.put("model_id", modelId != null ? modelId : 0L);
        entry.put("original_price", price);

        executor.call(connectionId, "POST", "/api/v2/product/update_price", Map.of(), body.toString());
    }
}
