package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client gọi TikTok Shop Fulfillment API thông qua {@link TiktokShopApiExecutor}.
 * Hỗ trợ các endpoint:
 * - POST /fulfillment/202309/packages/{package_id}/ship: Xác nhận gửi hàng / Arrange Shipment.
 * - GET /fulfillment/202309/packages/{package_id}/shipping_documents: Lấy tài liệu vận đơn (Waybill/Shipping Label PDF).
 */
@Slf4j
@Component
public class TiktokFulfillmentClient {

    public static final String PACKAGES_BASE_PATH = "/fulfillment/202309/packages/";

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;

    public TiktokFulfillmentClient(TiktokShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    /**
     * Xác nhận gửi hàng (Arrange Shipment) cho kiện hàng theo shopCipher.
     */
    public TiktokPackageResponse shipPackage(String shopCipher, String packageId, ShipPackageRequest request) {
        String path = PACKAGES_BASE_PATH + packageId + "/ship";
        String body = serializeBody(request);
        JsonNode data = executor.callByShopCipher(shopCipher, "POST", path, Map.of(), body);
        return convert(data, TiktokPackageResponse.class);
    }

    /**
     * Xác nhận gửi hàng (Arrange Shipment) cho kiện hàng theo connectionId.
     */
    public TiktokPackageResponse shipPackage(Long connectionId, String packageId, ShipPackageRequest request) {
        String path = PACKAGES_BASE_PATH + packageId + "/ship";
        String body = serializeBody(request);
        JsonNode data = executor.call(connectionId, "POST", path, Map.of(), body);
        return convert(data, TiktokPackageResponse.class);
    }

    /**
     * Lấy tài liệu nhãn vận đơn (Shipping Documents / Waybill) theo shopCipher.
     */
    public ShippingDocumentResponse getShippingDocuments(String shopCipher, String packageId, String documentType) {
        String path = PACKAGES_BASE_PATH + packageId + "/shipping_documents";
        Map<String, String> query = new LinkedHashMap<>();
        if (documentType != null && !documentType.isBlank()) {
            query.put("document_type", documentType);
        } else {
            query.put("document_type", "SL_PDF");
        }
        JsonNode data = executor.callByShopCipher(shopCipher, "GET", path, query, null);
        return convert(data, ShippingDocumentResponse.class);
    }

    /**
     * Lấy tài liệu nhãn vận đơn theo connectionId.
     */
    public ShippingDocumentResponse getShippingDocuments(Long connectionId, String packageId, String documentType) {
        String path = PACKAGES_BASE_PATH + packageId + "/shipping_documents";
        Map<String, String> query = new LinkedHashMap<>();
        if (documentType != null && !documentType.isBlank()) {
            query.put("document_type", documentType);
        } else {
            query.put("document_type", "SL_PDF");
        }
        JsonNode data = executor.call(connectionId, "GET", path, query, null);
        return convert(data, ShippingDocumentResponse.class);
    }

    private String serializeBody(Object obj) {
        if (obj == null) {
            return null;
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
