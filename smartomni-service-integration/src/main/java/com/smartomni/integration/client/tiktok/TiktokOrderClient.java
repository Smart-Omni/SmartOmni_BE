package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.OrderSearchRequest;
import com.smartomni.integration.dto.tiktok.OrderSearchResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client gọi TikTok Shop Order API thông qua {@link TiktokShopApiExecutor}.
 * Hỗ trợ các endpoint:
 * - GET /order/202309/orders/search: Tìm kiếm / phân trang danh sách đơn hàng.
 * - GET /order/202507/orders?ids=...: Lấy thông tin chi tiết một hoặc nhiều đơn hàng.
 */
@Slf4j
@Component
public class TiktokOrderClient {

    public static final String SEARCH_ORDERS_PATH = "/order/202309/orders/search";
    public static final String GET_ORDERS_PATH = "/order/202507/orders";

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    public TiktokOrderClient(TiktokShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    /**
     * Tìm kiếm danh sách đơn hàng theo shopCipher.
     */
    public OrderSearchResponse searchOrders(String shopCipher, OrderSearchRequest request) {
        Map<String, String> query = buildSearchQuery(request);
        JsonNode data = executor.callByShopCipher(shopCipher, "GET", SEARCH_ORDERS_PATH, query, null);
        return convert(data, OrderSearchResponse.class);
    }

    /**
     * Tìm kiếm danh sách đơn hàng theo connectionId.
     */
    public OrderSearchResponse searchOrders(Long connectionId, OrderSearchRequest request) {
        Map<String, String> query = buildSearchQuery(request);
        JsonNode data = executor.call(connectionId, "GET", SEARCH_ORDERS_PATH, query, null);
        return convert(data, OrderSearchResponse.class);
    }

    /**
     * Lấy thông tin chi tiết danh sách đơn hàng theo orderIds và shopCipher.
     */
    public List<TiktokOrderDetailResponse> getOrderDetails(String shopCipher, List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> query = Map.of("ids", String.join(",", orderIds));
        JsonNode data = executor.callByShopCipher(shopCipher, "GET", GET_ORDERS_PATH, query, null);
        return parseOrdersList(data);
    }

    /**
     * Lấy thông tin chi tiết danh sách đơn hàng theo orderIds và connectionId.
     */
    public List<TiktokOrderDetailResponse> getOrderDetails(Long connectionId, List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> query = Map.of("ids", String.join(",", orderIds));
        JsonNode data = executor.call(connectionId, "GET", GET_ORDERS_PATH, query, null);
        return parseOrdersList(data);
    }

    private Map<String, String> buildSearchQuery(OrderSearchRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        int pageSize = (request != null && request.getPageSize() != null) ? request.getPageSize() : 20;
        query.put("page_size", Integer.toString(pageSize));

        if (request != null) {
            if (request.getPageToken() != null && !request.getPageToken().isBlank()) {
                query.put("page_token", request.getPageToken());
            }
            if (request.getOrderStatus() != null && !request.getOrderStatus().isBlank()) {
                query.put("order_status", request.getOrderStatus());
            }
            if (request.getCreateTimeFrom() != null) {
                query.put("create_time_from", request.getCreateTimeFrom().toString());
            }
            if (request.getCreateTimeTo() != null) {
                query.put("create_time_to", request.getCreateTimeTo().toString());
            }
            if (request.getUpdateTimeFrom() != null) {
                query.put("update_time_from", request.getUpdateTimeFrom().toString());
            }
            if (request.getUpdateTimeTo() != null) {
                query.put("update_time_to", request.getUpdateTimeTo().toString());
            }
            if (request.getSortBy() != null && !request.getSortBy().isBlank()) {
                query.put("sort_by", request.getSortBy());
            }
            if (request.getSortType() != null && !request.getSortType().isBlank()) {
                query.put("sort_type", request.getSortType());
            }
        }
        return query;
    }

    private List<TiktokOrderDetailResponse> parseOrdersList(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return List.of();
        }
        JsonNode ordersNode = data.path("orders");
        if (ordersNode.isMissingNode() || ordersNode.isNull()) {
            ordersNode = data.path("order_list");
        }
        if (ordersNode.isArray()) {
            List<TiktokOrderDetailResponse> list = new ArrayList<>();
            for (JsonNode item : ordersNode) {
                list.add(convert(item, TiktokOrderDetailResponse.class));
            }
            return list;
        } else if (data.isArray()) {
            List<TiktokOrderDetailResponse> list = new ArrayList<>();
            for (JsonNode item : data) {
                list.add(convert(item, TiktokOrderDetailResponse.class));
            }
            return list;
        } else if (data.has("order_id")) {
            return List.of(convert(data, TiktokOrderDetailResponse.class));
        }
        return List.of();
    }

    private <T> T convert(JsonNode data, Class<T> type) {
        try {
            JsonNode source = data == null || data.isMissingNode() || data.isNull() ? mapper.createObjectNode() : data;
            return mapper.treeToValue(source, type);
        } catch (JsonProcessingException e) {
            throw new TiktokApiException("TikTok response did not match the expected shape for " + type.getSimpleName(),
                    200, null, null, false);
        }
    }
}
