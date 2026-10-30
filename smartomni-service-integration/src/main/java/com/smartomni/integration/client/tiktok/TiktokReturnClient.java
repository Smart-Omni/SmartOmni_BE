package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.ReturnSearchRequest;
import com.smartomni.integration.dto.tiktok.ReturnSearchResponse;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Client gọi TikTok Shop Return / Reverse API thông qua {@link TiktokShopApiExecutor}.
 * Hỗ trợ các endpoint:
 * - POST /return/202309/returns/search: Tìm kiếm / tra cứu danh sách yêu cầu trả hàng, hoàn tiền.
 */
@Slf4j
@Component
public class TiktokReturnClient {

    public static final String SEARCH_RETURNS_PATH = "/return/202309/returns/search";

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
        String body = serializeBody(request != null ? request : new ReturnSearchRequest());
        JsonNode data = executor.callByShopCipher(shopCipher, "POST", SEARCH_RETURNS_PATH, Map.of(), body);
        return convert(data, ReturnSearchResponse.class);
    }

    /**
     * Tra cứu danh sách yêu cầu hoàn hàng / trả hàng theo connectionId.
     */
    public ReturnSearchResponse searchReturns(Long connectionId, ReturnSearchRequest request) {
        String body = serializeBody(request != null ? request : new ReturnSearchRequest());
        JsonNode data = executor.call(connectionId, "POST", SEARCH_RETURNS_PATH, Map.of(), body);
        return convert(data, ReturnSearchResponse.class);
    }

    /**
     * Lấy thông tin chi tiết một yêu cầu trả hàng / hoàn tiền theo returnId.
     */
    public com.smartomni.integration.dto.tiktok.TiktokReturnResponse getReturnDetail(Long connectionId, String returnId) {
        String path = "/return/202309/returns/" + returnId;
        JsonNode data = executor.call(connectionId, "GET", path, Map.of(), null);
        return convert(data, com.smartomni.integration.dto.tiktok.TiktokReturnResponse.class);
    }

    /**
     * Duyệt đồng ý yêu cầu hoàn tiền / trả hàng.
     */
    public boolean approveReturn(Long connectionId, String returnId) {
        String path = "/return/202309/returns/" + returnId + "/approve";
        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), "{}");
        return data != null;
    }

    /**
     * Từ chối yêu cầu trả hàng / hoàn tiền kèm lý do.
     */
    public boolean rejectReturn(Long connectionId, String returnId, String rejectReason) {
        String path = "/return/202309/returns/" + returnId + "/reject";
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.put("reject_reason", rejectReason != null ? rejectReason : "REJECTED_BY_SELLER");
        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), body.toString());
        return data != null;
    }

    private String serializeBody(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new TiktokApiException("Failed to serialize request payload: " + e.getMessage(), 400, null, null, false);
        }
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
