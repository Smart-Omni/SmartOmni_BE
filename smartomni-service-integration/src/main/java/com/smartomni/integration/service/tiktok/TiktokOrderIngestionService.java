package com.smartomni.integration.service.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokOrderClient;
import com.smartomni.integration.dto.tiktok.TiktokOrderDetailResponse;
import com.smartomni.integration.dto.tiktok.TiktokWebhookPayload;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.service.catalog.CatalogMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service tiếp nhận và xử lý sự kiện đơn hàng từ TikTok Shop (Webhook / API) (Bước 2.2).
 * - Phân giải seller_sku / external_sku_id sang internal sku_id của SmartOmni qua CatalogMappingService.
 * - Chuẩn hóa đơn hàng sang WebhookOrderPayload, giữ nguyên timestamp create_time trên sàn.
 * - Đẩy message chuẩn hóa vào RabbitMQ exchange 'smartomni.order.exchange' cho Order Service tiêu thụ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TiktokOrderIngestionService {

    public static final String ORDER_EXCHANGE = "smartomni.order.exchange";
    public static final String ORDER_ROUTING_KEY = "order.incoming";
    public static final String PLATFORM_TIKTOK_SHOP = "TIKTOK_SHOP";

    public static final String RECONCILIATION_SKU_UNMAPPED = "SKU_UNMAPPED";

    private final CatalogMappingService catalogMappingService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TiktokOrderClient orderClient;

    /**
     * Handles an {@code ORDER_STATUS_CHANGE} webhook. The event only carries the order id and status, so the full
     * order is read with Get Order Detail ({@code GET /order/202507/orders}) and then normalized like a polled order.
     * Must run inside the tenant's scope.
     *
     * @throws BusinessException 400 {@code INVALID_PAYLOAD} when {@code data.order_id} is missing
     * @throws TiktokApiException when TikTok fails; transient failures should be answered with 503 so TikTok retries
     * @throws BusinessException 503 {@code TIKTOK_ORDER_NOT_READY} when TikTok does not return the order yet
     */
    public WebhookOrderPayload processOrderEvent(Long tenantId, MarketplaceConnection connection, JsonNode data) {
        String orderId = data == null ? null : data.path("order_id").asText(null);
        if (orderId == null || orderId.isBlank()) {
            throw new BusinessException("ORDER_STATUS_CHANGE webhook has no data.order_id", HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }
        List<TiktokOrderDetailResponse> details = orderClient.getOrderDetails(connection.getId(), List.of(orderId.trim()));
        TiktokOrderDetailResponse detail = details.stream()
                .filter(o -> orderId.trim().equals(o.getOrderId()))
                .findFirst()
                .orElse(details.size() == 1 ? details.get(0) : null);
        if (detail == null) {
            throw new BusinessException("TikTok did not return order " + orderId + " yet",
                    HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_ORDER_NOT_READY");
        }
        return ingestOrder(tenantId, detail);
    }

    /**
     * Tiếp nhận webhook payload sự kiện ORDER_STATUS_CHANGE từ TikTok Shop.
     */
    public WebhookOrderPayload processOrderWebhook(Long tenantId, TiktokWebhookPayload webhookPayload) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required and must be positive", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (webhookPayload == null || webhookPayload.getData() == null) {
            throw new BusinessException("Webhook payload data is required", HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }

        try {
            TiktokOrderDetailResponse order = objectMapper.treeToValue(webhookPayload.getData(), TiktokOrderDetailResponse.class);
            return ingestOrder(tenantId, order);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            log.error("Không thể phân tích dữ liệu order từ webhook payload: tenantId={}, error={}", tenantId, ex.getMessage(), ex);
            throw new BusinessException("Cannot parse order data from webhook: " + ex.getMessage(), HttpStatus.BAD_REQUEST, "ORDER_PARSE_FAILED");
        }
    }

    /**
     * Tiếp nhận và chuẩn hóa đối tượng đơn hàng TikTok sang WebhookOrderPayload.
     */
    public WebhookOrderPayload ingestOrder(Long tenantId, TiktokOrderDetailResponse order) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required and must be positive", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
            throw new BusinessException("Order detail and order_id must not be empty", HttpStatus.BAD_REQUEST, "INVALID_ORDER_ID");
        }

        log.info("Bắt đầu xử lý ingestion đơn hàng TikTok: tenantId={}, orderId={}, orderStatus={}",
                tenantId, order.getOrderId(), order.getOrderStatus());

        // 1. Ánh xạ từng line item sang internal sku_id qua CatalogMappingService
        List<WebhookOrderPayload.OrderItemPayload> items = new ArrayList<>();
        boolean unmappedSku = false;
        if (order.getLineItems() != null) {
            for (TiktokOrderDetailResponse.LineItem lineItem : order.getLineItems()) {
                Long internalSkuId = null;
                String sellerSku = lineItem.getSellerSku();
                String externalSkuId = lineItem.getSkuId();

                try {
                    // Ưu tiên tra cứu theo seller_sku
                    if (sellerSku != null && !sellerSku.isBlank()) {
                        internalSkuId = catalogMappingService.findInternalSkuId(tenantId, "tiktok_shop", sellerSku).orElse(null);
                    }
                    // Nếu chưa tìm thấy, tra cứu theo external_sku_id
                    if (internalSkuId == null && externalSkuId != null && !externalSkuId.isBlank()) {
                        internalSkuId = catalogMappingService.findInternalSkuId(tenantId, "tiktok_shop", externalSkuId).orElse(null);
                    }
                } catch (Exception ex) {
                    log.warn("Lỗi khi tra cứu SKU mapping cho line item: tenantId={}, orderId={}, sellerSku={}, externalSkuId={}: {}",
                            tenantId, order.getOrderId(), sellerSku, externalSkuId, ex.getMessage());
                }

                // SKU chưa ánh xạ: vẫn tạo đơn nhưng đánh dấu cần đối soát; không gán SKU khác và không trừ kho.
                if (internalSkuId == null) {
                    unmappedSku = true;
                    log.warn("SKU chưa được ánh xạ trong SmartOmni (chưa liên kết product_marketplace_links): tenantId={}, orderId={}, lineItemId={}, sellerSku={}, externalSkuId={}",
                            tenantId, order.getOrderId(), lineItem.getId(), sellerSku, externalSkuId);
                }

                BigDecimal unitPrice = lineItem.getSalePrice() != null
                        ? lineItem.getSalePrice()
                        : (lineItem.getOriginalPrice() != null ? lineItem.getOriginalPrice() : BigDecimal.ZERO);

                // Trích xuất imageUrl từ line item của TikTok Shop (fallback sang CatalogMappingService)
                String imageUrl = lineItem.getSkuImage();
                if (imageUrl == null || imageUrl.isBlank()) {
                    try {
                        if (sellerSku != null && !sellerSku.isBlank()) {
                            imageUrl = catalogMappingService.findImageUrl(tenantId, "tiktok_shop", sellerSku).orElse(null);
                        }
                        if (imageUrl == null && externalSkuId != null && !externalSkuId.isBlank()) {
                            imageUrl = catalogMappingService.findImageUrl(tenantId, "tiktok_shop", externalSkuId).orElse(null);
                        }
                    } catch (Exception ex) {
                        log.warn("Lỗi khi tra cứu image_url từ catalog mapping: tenantId={}, orderId={}, sellerSku={}, externalSkuId={}: {}",
                                tenantId, order.getOrderId(), sellerSku, externalSkuId, ex.getMessage());
                    }
                }

                items.add(WebhookOrderPayload.OrderItemPayload.builder()
                        .skuId(internalSkuId)
                        .externalSkuId(externalSkuId)
                        .sellerSku(sellerSku)
                        .productName(lineItem.getProductName())
                        .quantity(lineItem.getQuantity() != null ? lineItem.getQuantity() : 1)
                        .unitPrice(unitPrice)
                        .imageUrl(imageUrl)
                        .build());
            }
        }

        // 2. Trích xuất thông tin khách hàng và địa chỉ giao hàng
        String customerName = null;
        String customerPhone = null;
        String shippingAddress = null;
        if (order.getRecipientAddress() != null) {
            customerName = order.getRecipientAddress().getName();
            customerPhone = order.getRecipientAddress().getPhoneNumber();
            shippingAddress = order.getRecipientAddress().getFullAddress();
            if (shippingAddress == null || shippingAddress.isBlank()) {
                shippingAddress = buildShippingAddress(order.getRecipientAddress());
            }
        }

        // 3. Trích xuất tổng tiền đơn hàng
        BigDecimal totalAmount = BigDecimal.ZERO;
        if (order.getPayment() != null && order.getPayment().getTotalAmount() != null) {
            totalAmount = order.getPayment().getTotalAmount();
        }

        // 4. Giữ nguyên timestamp create_time thực tế trên sàn TikTok
        Long createTime = order.getCreateTime();
        Instant createdAt = null;
        if (createTime != null && createTime > 0) {
            createdAt = (createTime > 100_000_000_000L)
                    ? Instant.ofEpochMilli(createTime)
                    : Instant.ofEpochSecond(createTime);
        } else {
            createdAt = Instant.now();
            createTime = createdAt.getEpochSecond();
        }

        // 5. Đóng gói vào WebhookOrderPayload chuẩn của SmartOmni
        WebhookOrderPayload payload = WebhookOrderPayload.builder()
                .tenantId(tenantId)
                .platform(PLATFORM_TIKTOK_SHOP)
                .platformOrderId(order.getOrderId())
                .orderStatus(order.getOrderStatus())
                .customerName(customerName)
                .customerPhone(customerPhone)
                .shippingAddress(shippingAddress)
                .totalAmount(totalAmount)
                .createTime(createTime)
                .createdAt(createdAt)
                .platformUpdateTime(epochSeconds(order.getUpdateTime()))
                .reconciliationReason(unmappedSku ? RECONCILIATION_SKU_UNMAPPED : null)
                .items(items)
                .build();

        // 6. Đẩy message chuẩn hóa vào RabbitMQ Exchange
        try {
            rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_ROUTING_KEY, payload);
            log.info("Đã gửi WebhookOrderPayload thành công vào RabbitMQ: exchange={}, routingKey={}, tenantId={}, orderId={}, itemsCount={}",
                    ORDER_EXCHANGE, ORDER_ROUTING_KEY, tenantId, order.getOrderId(), items.size());
        } catch (Exception ex) {
            log.error("Lỗi khi đẩy WebhookOrderPayload vào RabbitMQ: tenantId={}, orderId={}, error={}",
                    tenantId, order.getOrderId(), ex.getMessage(), ex);
            throw new BusinessException("Failed to publish order message to queue: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR, "QUEUE_PUBLISH_FAILED");
        }

        return payload;
    }

    /**
     * Overload hỗ trợ tenantId dạng String.
     */
    public WebhookOrderPayload ingestOrder(String tenantId, TiktokOrderDetailResponse order) {
        return ingestOrder(parseTenantId(tenantId), order);
    }

    /**
     * Overload hỗ trợ tenantId dạng String cho Webhook payload.
     */
    public WebhookOrderPayload processOrderWebhook(String tenantId, TiktokWebhookPayload webhookPayload) {
        return processOrderWebhook(parseTenantId(tenantId), webhookPayload);
    }

    private Long parseTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("tenantId is required", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        try {
            return Long.parseLong(tenantId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("tenantId must be a valid number: " + tenantId, HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
    }

    /** TikTok times are epoch seconds; tolerate millis. Null when absent. */
    private static Long epochSeconds(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value > 100_000_000_000L ? value / 1000 : value;
    }

    private String buildShippingAddress(TiktokOrderDetailResponse.RecipientAddress addr) {
        if (addr == null) return null;
        String combined = Stream.of(
                addr.getAddressLine1(),
                addr.getAddressLine2(),
                addr.getDistrict(),
                addr.getCity(),
                addr.getState(),
                addr.getPostalCode(),
                addr.getRegionCode()
        ).filter(s -> s != null && !s.isBlank())
         .collect(Collectors.joining(", "));
        return combined.isBlank() ? null : combined;
    }
}
