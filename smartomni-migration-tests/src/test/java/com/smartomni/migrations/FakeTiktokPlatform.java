package com.smartomni.migrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scripted TikTok catalog keyed by connection id (the executor is the seam above the signed HTTP client, which has its own tests).
 * Search paginates with opaque tokens ("t&lt;offset&gt;"), detail adds description and sales attributes.
 */
public class FakeTiktokPlatform extends TiktokShopApiExecutor {

    public record Request(long connectionId, String path, Map<String, String> query) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, List<ObjectNode>> products = new HashMap<>();
    private final Map<Long, List<ObjectNode>> warehouses = new HashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public final Set<String> failingDetailIds = new HashSet<>();
    public boolean failSearch;

    public FakeTiktokPlatform() {
        super(null, null, null);
    }

    public void reset() {
        products.clear();
        warehouses.clear();
        requests.clear();
        failingDetailIds.clear();
        failSearch = false;
    }

    /** skus: {externalSkuId, sellerSku, price} triples. */
    public void addProduct(long connectionId, String productId, String title, String status, String[]... skus) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", productId);
        p.put("title", title);
        p.put("status", status);
        p.put("description", "Description of " + title);
        ArrayNode skuArray = p.putArray("skus");
        for (String[] s : skus) {
            ObjectNode sku = skuArray.addObject();
            sku.put("id", s[0]);
            if (s[1] != null) {
                sku.put("seller_sku", s[1]);
            }
            if (s[2] != null) {
                sku.putObject("price").put("currency", "VND").put("sale_price", s[2]);
            }
            sku.putArray("inventory").addObject().put("warehouse_id", "W1").put("quantity", 10);
            sku.putArray("sales_attributes").addObject().put("name", "Size").put("value_name", "V-" + s[0]);
        }
        products.computeIfAbsent(connectionId, k -> new ArrayList<>()).add(p);
    }

    public void setPrice(long connectionId, String externalSkuId, String price) {
        for (ObjectNode p : products.getOrDefault(connectionId, List.of())) {
            for (JsonNode sku : p.get("skus")) {
                if (externalSkuId.equals(sku.get("id").asText())) {
                    if (price == null) {
                        ((ObjectNode) sku).remove("price");
                    } else {
                        ((ObjectNode) sku).putObject("price").put("currency", "VND").put("sale_price", price);
                    }
                }
            }
        }
    }

    public void addWarehouse(long connectionId, String id, String name, boolean isDefault) {
        ObjectNode w = mapper.createObjectNode();
        w.put("id", id).put("name", name).put("is_default", isDefault).put("effect_status", "ENABLED").put("type", "SALES_WAREHOUSE");
        w.putObject("address").put("city", "Ha Noi").put("region_code", "VN");
        warehouses.computeIfAbsent(connectionId, k -> new ArrayList<>()).add(w);
    }

    public long searchRequests() {
        return requests.stream().filter(r -> r.path().contains("/products/search")).count();
    }

    @Override
    public JsonNode call(Long connectionId, String method, String path, Map<String, String> query, String body) {
        requests.add(new Request(connectionId, path, Map.copyOf(query)));
        if (path.startsWith("/logistics/202309/warehouses")) {
            ObjectNode data = mapper.createObjectNode();
            data.set("warehouses", mapper.valueToTree(warehouses.getOrDefault(connectionId, List.of())));
            return data;
        }
        List<ObjectNode> all = products.getOrDefault(connectionId, List.of());
        if (path.startsWith("/product/202502/products/search")) {
            if (failSearch) {
                throw new TiktokApiException("search failed", 500, null, "REQ", true);
            }
            int pageSize = Integer.parseInt(query.get("page_size"));
            String token = query.getOrDefault("page_token", "");
            int offset = token.isEmpty() ? 0 : Integer.parseInt(token.substring(1));
            int end = Math.min(all.size(), offset + pageSize);
            ObjectNode data = mapper.createObjectNode();
            ArrayNode page = data.putArray("products");
            for (ObjectNode p : all.subList(offset, end)) {
                ObjectNode summary = p.deepCopy();
                summary.remove("description");
                page.add(summary);
            }
            data.put("next_page_token", end < all.size() ? "t" + end : "");
            data.put("total_count", all.size());
            return data;
        }
        if (path.startsWith("/product/202309/products/")) {
            String id = path.substring(path.lastIndexOf('/') + 1);
            if (failingDetailIds.contains(id)) {
                throw new TiktokApiException("detail failed", 500, null, "REQ", true);
            }
            return all.stream().filter(p -> id.equals(p.get("id").asText())).findFirst()
                    .orElseThrow(() -> new TiktokApiException("not found", 404, 12345, "REQ", false));
        }
        throw new IllegalStateException("unexpected path " + path);
    }
}
