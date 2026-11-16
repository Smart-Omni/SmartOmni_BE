package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.dto.tiktok.TiktokShippingProviderDto;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client gọi TikTok Shop Fulfillment API thông qua {@link TiktokShopApiExecutor}.
 * Hỗ trợ các endpoint:
 * - GET /fulfillment/202309/shipping_providers: Lấy danh sách đơn vị & dịch vụ vận chuyển.
 * - POST /fulfillment/202309/packages/{package_id}/ship: Xác nhận gửi hàng / Arrange Shipment.
 * - GET /fulfillment/202309/packages/{package_id}/shipping_label/download: Tải PDF vận đơn theo package.
 * - GET /fulfillment/202309/orders/{order_id}/shipping_label/download: Tải PDF vận đơn theo order.
 * - GET /fulfillment/202309/packages/{package_id}/shipping_documents: Lấy tài liệu vận đơn (Waybill/Shipping Label PDF).
 */
@Slf4j
@Component
public class TiktokFulfillmentClient {

    public static final String PACKAGES_BASE_PATH = "/fulfillment/202309/packages/";
    public static final String ORDERS_BASE_PATH = "/fulfillment/202309/orders/";
    public static final String SHIPPING_PROVIDERS_PATH = "/fulfillment/202309/shipping_providers";
    public static final String SHIPPING_LABEL_UNAVAILABLE_CODE = "SHIPPING_LABEL_UNAVAILABLE";

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

    /**
     * Lấy danh sách đơn vị vận chuyển từ Simulator (GET /fulfillment/202309/shipping_providers).
     */
    public List<TiktokShippingProviderDto> getShippingProviders(Long connectionId) {
        JsonNode data = executor.call(connectionId, "GET", SHIPPING_PROVIDERS_PATH, Map.of(), null);
        if (data == null || data.isMissingNode() || data.isNull()) {
            return List.of();
        }
        JsonNode providersNode = data.has("shipping_providers") ? data.get("shipping_providers") : data;
        if (!providersNode.isArray()) {
            return List.of();
        }
        List<TiktokShippingProviderDto> providers = new ArrayList<>();
        for (JsonNode node : providersNode) {
            providers.add(convert(node, TiktokShippingProviderDto.class));
        }
        return providers;
    }

    /**
     * Tải binary PDF vận đơn theo packageId (GET /fulfillment/202309/packages/{packageId}/shipping_label/download).
     */
    public byte[] downloadShippingLabelByPackage(Long connectionId, String packageId) {
        String path = PACKAGES_BASE_PATH + packageId + "/shipping_label/download";
        return extractPdfOrThrow(executor.callBinary(connectionId, "GET", path, Map.of(), null));
    }

    /**
     * Tải binary PDF vận đơn theo orderId (GET /fulfillment/202309/orders/{orderId}/shipping_label/download).
     */
    public byte[] downloadShippingLabelByOrder(Long connectionId, String orderId) {
        String path = ORDERS_BASE_PATH + orderId + "/shipping_label/download";
        return extractPdfOrThrow(executor.callBinary(connectionId, "GET", path, Map.of(), null));
    }

    private byte[] extractPdfOrThrow(TiktokHttpTransport.BinaryResponse response) {
        if (response == null) {
            throw new BusinessException("Empty response from TikTok Simulator when downloading shipping label",
                    HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        int status = response.status();
        byte[] body = response.body();
        if (status == 400) {
            throw new BusinessException(extractErrorMessage(body, "Invalid shipping label download request"),
                    HttpStatus.BAD_REQUEST, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        if (status == 404) {
            throw new BusinessException(extractErrorMessage(body, "Shipping label not found"),
                    HttpStatus.NOT_FOUND, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        if (status == 401 || status == 403) {
            throw new BusinessException(extractErrorMessage(body, "Unauthorized to download shipping label"),
                    HttpStatus.valueOf(status), SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException(extractErrorMessage(body, "TikTok Simulator failed to generate shipping label (HTTP " + status + ")"),
                    HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }

        String contentType = response.contentType() != null ? response.contentType().toLowerCase() : "";
        if (!contentType.contains("application/pdf")) {
            throw new BusinessException(extractErrorMessage(body, "Shipping label response is not a PDF"),
                    HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        if (body == null || body.length == 0) {
            throw new BusinessException("Shipping label PDF body is empty",
                    HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        return body;
    }

    private String extractErrorMessage(byte[] body, String fallback) {
        if (body == null || body.length == 0) {
            return fallback;
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(text);
                if (node.hasNonNull("message") && !node.get("message").asText().isBlank()) {
                    return node.get("message").asText();
                }
                if (node.hasNonNull("error") && !node.get("error").asText().isBlank()) {
                    return node.get("error").asText();
                }
                if (node.hasNonNull("msg") && !node.get("msg").asText().isBlank()) {
                    return node.get("msg").asText();
                }
            } catch (Exception ignored) {
            }
        }
        return fallback;
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
