package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.dto.tiktok.TiktokReturnRecord;
import com.smartomni.integration.dto.tiktok.TiktokReturnResponse;
import org.springframework.http.HttpStatus;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client gọi TikTok Shop Return / Reverse API thông qua {@link TiktokShopApiExecutor}.
 * Khớp với TikTok Simulator develop ({@code /return_refund/202309/*}).
 */
@Slf4j
@Component
public class TiktokReturnClient {

    public static final String SEARCH_RETURNS_PATH = "/return_refund/202309/returns/search";
    public static final String REJECT_REASONS_PATH = "/return_refund/202309/reject_reasons";

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    public TiktokReturnClient(TiktokShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    /**
     * Tra cứu danh sách yêu cầu hoàn hàng / trả hàng theo shopCipher.
     */
    public ReturnSearchResponse searchReturns(String shopCipher, ReturnSearchRequest request) {
        Map<String, String> query = buildSearchQuery(request);
        String body = serializeSearchBody(request);
        JsonNode data = executor.callByShopCipher(shopCipher, "POST", SEARCH_RETURNS_PATH, query, body);
        return convert(data, ReturnSearchResponse.class);
    }

    /**
     * Tra cứu danh sách yêu cầu hoàn hàng / trả hàng theo connectionId.
     */
    public ReturnSearchResponse searchReturns(Long connectionId, ReturnSearchRequest request) {
        Map<String, String> query = buildSearchQuery(request);
        String body = serializeSearchBody(request);
        JsonNode data = executor.call(connectionId, "POST", SEARCH_RETURNS_PATH, query, body);
        return convert(data, ReturnSearchResponse.class);
    }

    /**
     * Chi tiết một yêu cầu trả hàng: Search Returns lọc theo {@code return_ids} (TikTok không có endpoint
     * GET chi tiết riêng; {@code /records} chỉ là lịch sử).
     *
     * @return bản ghi, hoặc {@code null} khi sàn không trả về id này
     */
    public TiktokReturnResponse getReturnDetail(Long connectionId, String returnId) {
        requireId(returnId, "returnId");
        ReturnSearchResponse page = searchReturns(connectionId, ReturnSearchRequest.builder()
                .returnIds(List.of(returnId.trim())).pageSize(10).build());
        if (page == null || page.getReturns() == null) {
            return null;
        }
        return page.getReturns().stream().filter(r -> returnId.trim().equals(r.getReturnId())).findFirst().orElse(null);
    }

    /** Lịch sử xử lý một yêu cầu trả hàng (GET /return_refund/202309/returns/{returnId}/records). */
    public List<TiktokReturnRecord> getReturnRecords(Long connectionId, String returnId) {
        requireId(returnId, "returnId");
        JsonNode data = executor.call(connectionId, "GET",
                "/return_refund/202309/returns/" + returnId.trim() + "/records", Map.of(), null);
        List<TiktokReturnRecord> records = new ArrayList<>();
        if (data != null) {
            for (JsonNode node : data.path("records")) {
                records.add(convert(node, TiktokReturnRecord.class));
            }
        }
        return records;
    }

    private static void requireId(String id, String name) {
        if (id == null || !id.trim().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException("Invalid " + name, HttpStatus.BAD_REQUEST, "INVALID_" + name.toUpperCase());
        }
    }

    /**
     * Duyệt đồng ý yêu cầu hoàn tiền / trả hàng (POST /return_refund/202309/returns/{returnId}/approve).
     */
    public boolean approveReturn(Long connectionId, String returnId) {
        String path = "/return_refund/202309/returns/" + returnId + "/approve";
        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), "{}");
        return data != null;
    }

    /**
     * Từ chối yêu cầu trả hàng / hoàn tiền kèm lý do (POST /return_refund/202309/returns/{returnId}/reject).
     */
    public boolean rejectReturn(Long connectionId, String returnId, String rejectReason) {
        String path = "/return_refund/202309/returns/" + returnId + "/reject";
        ObjectNode body = mapper.createObjectNode();
        body.put("reject_reason", rejectReason != null ? rejectReason : "ITEM_AS_DESCRIBED");
        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), body.toString());
        return data != null;
    }

    /**
     * Lấy danh sách lý do từ chối hợp lệ từ TikTok Simulator (GET /return_refund/202309/reject_reasons).
     */
    public JsonNode getRejectReasons(Long connectionId, String returnOrCancelId) {
        Map<String, String> query = Map.of("return_or_cancel_id", returnOrCancelId);
        return executor.call(connectionId, "GET", REJECT_REASONS_PATH, query, null);
    }

    /**
     * Kiểm tra điều kiện aftersale của đơn hàng (GET /return_refund/202512/orders/{orderId}/aftersale_eligibility).
     */
    public JsonNode getAftersaleEligibility(Long connectionId, String orderId) {
        String path = "/return_refund/202512/orders/" + orderId + "/aftersale_eligibility";
        return executor.call(connectionId, "GET", path, Map.of(), null);
    }

    private Map<String, String> buildSearchQuery(ReturnSearchRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        int pageSize = (request != null && request.getPageSize() != null)
                ? Math.max(10, Math.min(request.getPageSize(), 50))
                : 20;
        query.put("page_size", Integer.toString(pageSize));
        if (request != null && request.getPageToken() != null && !request.getPageToken().isBlank()) {
            query.put("page_token", request.getPageToken().trim());
        }
        return query;
    }

    private String serializeSearchBody(ReturnSearchRequest request) {
        ObjectNode body = mapper.createObjectNode();
        if (request != null) {
            if (request.getReturnStatus() != null && !request.getReturnStatus().isBlank()) {
                body.put("return_status", request.getReturnStatus().trim());
            }
            if (request.getOrderId() != null && !request.getOrderId().isBlank()) {
                ArrayNode orderIds = body.putArray("order_ids");
                orderIds.add(request.getOrderId().trim());
            }
            if (request.getReturnType() != null && !request.getReturnType().isBlank()) {
                ArrayNode returnTypes = body.putArray("return_types");
                returnTypes.add(request.getReturnType().trim());
            }
            if (request.getCreateTimeFrom() != null) {
                body.put("create_time_ge", request.getCreateTimeFrom());
            }
            if (request.getCreateTimeTo() != null) {
                body.put("create_time_lt", request.getCreateTimeTo());
            }
            if (request.getUpdateTimeFrom() != null) {
                body.put("update_time_ge", request.getUpdateTimeFrom());
            }
            if (request.getUpdateTimeTo() != null) {
                body.put("update_time_lt", request.getUpdateTimeTo());
            }
            if (request.getReturnIds() != null && !request.getReturnIds().isEmpty()) {
                ArrayNode ids = body.putArray("return_ids");
                request.getReturnIds().forEach(ids::add);
            }
        }
        return body.toString();
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
