package com.smartomni.integration.client.tiktok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.config.TiktokProperties;
import com.smartomni.integration.dto.tiktok.ShipPackageRequest;
import com.smartomni.integration.dto.tiktok.ShippingDocumentResponse;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokPackageResponse;
import com.smartomni.integration.dto.tiktok.TiktokShippingProviderDto;
import com.smartomni.integration.service.tiktok.TiktokShopApiExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client gọi TikTok Shop Fulfillment API thông qua {@link TiktokShopApiExecutor}.
 * Hỗ trợ các endpoint trên TikTok Simulator develop:
 * - GET /fulfillment/202309/packages/{package_id}: Lấy thông tin kiện hàng & đơn vị vận chuyển.
 * - POST /fulfillment/202309/packages/{package_id}/ship: Xác nhận gửi hàng / Arrange Shipment.
 * - GET /fulfillment/202309/packages/{package_id}/shipping_documents: Lấy tài liệu vận đơn (doc_url PDF).
 */
@Slf4j
@Component
public class TiktokFulfillmentClient {

    public static final String PACKAGES_BASE_PATH = "/fulfillment/202309/packages/";
    public static final String SHIPPING_LABEL_UNAVAILABLE_CODE = "SHIPPING_LABEL_UNAVAILABLE";

    private final TiktokShopApiExecutor executor;
    private final ObjectMapper mapper;
    private final TiktokOrderClient orderClient;
    private final TiktokProperties properties;

    public TiktokFulfillmentClient(TiktokShopApiExecutor executor, ObjectMapper mapper) {
        this(executor, mapper, null, null);
    }

    public TiktokFulfillmentClient(TiktokShopApiExecutor executor, ObjectMapper mapper,
                                   TiktokOrderClient orderClient) {
        this(executor, mapper, orderClient, null);
    }

    @Autowired
    public TiktokFulfillmentClient(TiktokShopApiExecutor executor, ObjectMapper mapper,
                                   @Autowired(required = false) TiktokOrderClient orderClient,
                                   @Autowired(required = false) TiktokProperties properties) {
        this.executor = executor;
        this.mapper = mapper;
        this.orderClient = orderClient;
        this.properties = properties;
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
     * Get Package Detail (GET /fulfillment/202309/packages/{package_id}): tracking number and carrier of a package.
     * The tracking number is absent until shipment is arranged.
     */
    public TiktokPackageResponse getPackageDetail(Long connectionId, String packageId) {
        JsonNode data = executor.call(connectionId, "GET", PACKAGES_BASE_PATH + packageId, Map.of(), null);
        return convert(data, TiktokPackageResponse.class);
    }

    /**
     * Lấy tài liệu nhãn vận đơn (Shipping Documents / Waybill) theo shopCipher.
     */
    public ShippingDocumentResponse getShippingDocuments(String shopCipher, String packageId, String documentType) {
        String path = PACKAGES_BASE_PATH + packageId + "/shipping_documents";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("document_type", normalizeDocumentType(documentType));
        JsonNode data = executor.callByShopCipher(shopCipher, "GET", path, query, null);
        return convert(data, ShippingDocumentResponse.class);
    }

    /**
     * Lấy tài liệu nhãn vận đơn theo connectionId.
     */
    public ShippingDocumentResponse getShippingDocuments(Long connectionId, String packageId, String documentType) {
        String path = PACKAGES_BASE_PATH + packageId + "/shipping_documents";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("document_type", normalizeDocumentType(documentType));
        JsonNode data = executor.call(connectionId, "GET", path, query, null);
        return convert(data, ShippingDocumentResponse.class);
    }

    /**
     * Lấy thông tin đơn vị vận chuyển của TikTok Simulator (D3).
     * Nếu có packageId sẽ truy vấn GET /fulfillment/202309/packages/{packageId}; ngược lại trả carrier chuẩn của Simulator.
     */
    public List<TiktokShippingProviderDto> getShippingProviders(Long connectionId) {
        return getShippingProviders(connectionId, null);
    }

    public List<TiktokShippingProviderDto> getShippingProviders(Long connectionId, String packageId) {
        if (packageId != null && !packageId.isBlank()) {
            JsonNode pkg = executor.call(connectionId, "GET", PACKAGES_BASE_PATH + packageId.trim(), Map.of(), null);
            String providerId = pkg != null && pkg.hasNonNull("shipping_provider_id")
                    ? pkg.get("shipping_provider_id").asText() : "SIM-CARRIER-1";
            String providerName = pkg != null && pkg.hasNonNull("shipping_provider_name")
                    ? pkg.get("shipping_provider_name").asText() : "Simulator Express (fake carrier)";
            String handover = pkg != null && pkg.hasNonNull("handover_method")
                    ? pkg.get("handover_method").asText() : "PICKUP";
            return List.of(TiktokShippingProviderDto.builder()
                    .shippingProviderId(providerId)
                    .shippingProviderName(providerName)
                    .shippingServiceId(handover)
                    .shippingServiceName("Platform " + handover)
                    .build());
        }
        return List.of(
                TiktokShippingProviderDto.builder()
                        .shippingProviderId("SIM-CARRIER-1")
                        .shippingProviderName("Simulator Express (fake carrier)")
                        .shippingServiceId("PICKUP")
                        .shippingServiceName("Carrier Pickup")
                        .build(),
                TiktokShippingProviderDto.builder()
                        .shippingProviderId("SIM-CARRIER-1")
                        .shippingProviderName("Simulator Express (fake carrier)")
                        .shippingServiceId("DROP_OFF")
                        .shippingServiceName("Seller Drop-off")
                        .build()
        );
    }

    /**
     * Tải binary PDF vận đơn theo packageId qua GET /fulfillment/202309/packages/{packageId}/shipping_documents + doc_url (D5).
     */
    public byte[] downloadShippingLabelByPackage(Long connectionId, String packageId) {
        return downloadShippingLabelByPackage(connectionId, packageId, "SHIPPING_LABEL");
    }

    public byte[] downloadShippingLabelByPackage(Long connectionId, String packageId, String documentType) {
        ShippingDocumentResponse doc;
        try {
            doc = getShippingDocuments(connectionId, packageId, documentType);
        } catch (TiktokApiException e) {
            throw mapApiException(e);
        }
        String docUrl = doc != null ? (doc.getDocUrl() != null ? doc.getDocUrl() : doc.getFileUrl()) : null;
        if (docUrl == null || docUrl.isBlank()) {
            throw new BusinessException("Shipping label doc_url is unavailable",
                    HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        return fetchPdfFromUrl(docUrl);
    }

    /**
     * Tải binary PDF vận đơn theo orderId bằng cách tra package_id từ GET /order/202507/orders?ids={orderId} (D5).
     */
    public byte[] downloadShippingLabelByOrder(Long connectionId, String orderId) {
        return downloadShippingLabelByOrder(connectionId, orderId, "SHIPPING_LABEL");
    }

    public byte[] downloadShippingLabelByOrder(Long connectionId, String orderId, String documentType) {
        if (orderClient == null) {
            throw new BusinessException("Order client is required to resolve packageId from orderId",
                    HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        TiktokOrderDetailResponse order;
        try {
            order = orderClient.getOrderDetail(connectionId, orderId);
        } catch (TiktokApiException e) {
            throw mapApiException(e);
        }
        String packageId = resolvePackageId(order);
        if (packageId == null) {
            throw new BusinessException("No package found for orderId: " + orderId,
                    HttpStatus.NOT_FOUND, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
        return downloadShippingLabelByPackage(connectionId, packageId, documentType);
    }

    private byte[] fetchPdfFromUrl(String docUrl) {
        String resolvedUrl = rewriteLocalhostUrlIfNeeded(docUrl);
        try {
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<byte[]> response = restTemplate.getForEntity(resolvedUrl, byte[].class);
            byte[] bytes = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || bytes == null || bytes.length == 0) {
                throw new BusinessException("Shipping label PDF is empty or unavailable",
                        HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
            }
            return bytes;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            log.error("Failed to download shipping label PDF from {}: {}", resolvedUrl, ex.getMessage());
            throw new BusinessException("Failed to fetch shipping label PDF from TikTok Simulator",
                    HttpStatus.BAD_GATEWAY, SHIPPING_LABEL_UNAVAILABLE_CODE);
        }
    }

    private String rewriteLocalhostUrlIfNeeded(String docUrl) {
        if (docUrl == null || properties == null || properties.getOpenApiBaseUrl() == null) {
            return docUrl;
        }
        try {
            java.net.URI docUri = java.net.URI.create(docUrl);
            String docHost = docUri.getHost();
            if (!"localhost".equalsIgnoreCase(docHost) && !"127.0.0.1".equals(docHost)) {
                return docUrl;
            }
            java.net.URI baseUri = java.net.URI.create(properties.getOpenApiBaseUrl());
            if (baseUri.getHost() == null || "localhost".equalsIgnoreCase(baseUri.getHost())
                    || "127.0.0.1".equals(baseUri.getHost())) {
                return docUrl;
            }
            String pathAndQuery = docUri.getRawPath()
                    + (docUri.getRawQuery() != null ? "?" + docUri.getRawQuery() : "");
            String base = properties.getOpenApiBaseUrl().replaceAll("/+$", "");
            return base + pathAndQuery;
        } catch (Exception ignored) {
            return docUrl;
        }
    }

    private static BusinessException mapApiException(TiktokApiException e) {
        int status = e.getHttpStatus();
        HttpStatus resolved = switch (status) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new BusinessException(e.getMessage(), resolved, SHIPPING_LABEL_UNAVAILABLE_CODE);
    }

    private static String normalizeDocumentType(String documentType) {
        if (documentType == null || documentType.isBlank()) {
            return "SHIPPING_LABEL";
        }
        String upper = documentType.trim().toUpperCase(Locale.ROOT);
        if ("SL_PDF".equals(upper) || "WAYBILL_PDF".equals(upper)) {
            return "SHIPPING_LABEL";
        }
        return upper;
    }

    private static String resolvePackageId(TiktokOrderDetailResponse order) {
        if (order == null) {
            return null;
        }
        if (order.getPackageId() != null && !order.getPackageId().isBlank()) {
            return order.getPackageId().trim();
        }
        if (order.getPackageList() != null && !order.getPackageList().isEmpty()) {
            String pkgId = order.getPackageList().get(0).getPackageId();
            if (pkgId != null && !pkgId.isBlank()) {
                return pkgId.trim();
            }
        }
        if (order.getLineItems() != null) {
            for (var item : order.getLineItems()) {
                if (item.getPackageId() != null && !item.getPackageId().isBlank()) {
                    return item.getPackageId().trim();
                }
            }
        }
        return null;
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
