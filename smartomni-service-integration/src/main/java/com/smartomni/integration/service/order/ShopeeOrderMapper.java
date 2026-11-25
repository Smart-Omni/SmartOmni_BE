package com.smartomni.integration.service.order;

import com.smartomni.common.dto.WebhookOrderPayload;
import com.smartomni.integration.client.shopee.ShopeeOrderClient;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderItemDetail;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.ShopeeOrderDetail;
import com.smartomni.integration.service.catalog.CatalogMappingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShopeeOrderMapper {

    private final CatalogMappingStore store;

    /** Reconciliation reason when a line item has no SmartOmni SKU (same value as the TikTok ingestion). */
    public static final String RECONCILIATION_SKU_UNMAPPED = "SKU_UNMAPPED";

    /**
     * Converts a Shopee order detail into the shared order message ({@link WebhookOrderPayload}, snake_case).
     * The status is the RAW Shopee status: mapping happens only in order-service ({@code ShopeeOrderStatusMapper}).
     */
    public WebhookOrderPayload toPayload(Long tenantId, Long connectionId, ShopeeOrderDetail order) {
        String customerName = null;
        String customerPhone = null;
        String fullAddress = null;

        if (order.recipientAddress() != null) {
            customerName = order.recipientAddress().name();
            customerPhone = order.recipientAddress().phone();
            fullAddress = order.recipientAddress().fullAddress();
        }
        if (customerName == null || customerName.isBlank()) {
            customerName = order.buyerUsername();
        }

        List<WebhookOrderPayload.OrderItemPayload> itemPayloads = new ArrayList<>();
        boolean unmappedSku = false;
        if (order.items() != null) {
            for (OrderItemDetail item : order.items()) {
                Long resolvedSkuId = resolveSkuId(connectionId, item);
                if (resolvedSkuId == null) {
                    unmappedSku = true;
                }

                String skuCode = (item.modelSku() != null && !item.modelSku().isBlank())
                        ? item.modelSku()
                        : item.itemSku();

                String externalSkuId = String.valueOf(item.modelId() != 0 ? item.modelId() : item.itemId());

                itemPayloads.add(WebhookOrderPayload.OrderItemPayload.builder()
                        .skuId(resolvedSkuId)
                        .sellerSku(skuCode)
                        .externalSkuId(externalSkuId)
                        .productName(item.itemName() + (item.modelName() != null && !item.modelName().isBlank() ? " - " + item.modelName() : ""))
                        .quantity(item.quantityPurchased())
                        .unitPrice(item.discountedPrice())
                        .build());
            }
        }

        return WebhookOrderPayload.builder()
                .tenantId(tenantId)
                .platform("SHOPEE")
                .platformOrderId(order.orderSn())
                .orderStatus(order.orderStatus())
                .customerName(customerName)
                .customerPhone(customerPhone)
                .shippingAddress(fullAddress)
                .totalAmount(order.totalAmount())
                .createTime(order.createTime())
                .platformUpdateTime(order.updateTime())
                .reconciliationReason(unmappedSku ? RECONCILIATION_SKU_UNMAPPED : null)
                .items(itemPayloads)
                .build();
    }

    private Long resolveSkuId(Long connectionId, OrderItemDetail item) {
        String externalSkuId = String.valueOf(item.modelId() != 0 ? item.modelId() : item.itemId());

        // 1. Try finding by marketplace link
        Optional<CatalogMappingStore.LinkRef> link = store.findLinkByExternalSku(connectionId, externalSkuId);
        if (link.isPresent()) {
            return link.get().skuId();
        }

        // 2. Try finding by model_sku
        if (item.modelSku() != null && !item.modelSku().isBlank()) {
            Optional<CatalogMappingStore.SkuRef> sku = store.findSkuByCode(item.modelSku());
            if (sku.isPresent()) {
                return sku.get().id();
            }
        }

        // 3. Try finding by item_sku
        if (item.itemSku() != null && !item.itemSku().isBlank()) {
            Optional<CatalogMappingStore.SkuRef> sku = store.findSkuByCode(item.itemSku());
            if (sku.isPresent()) {
                return sku.get().id();
            }
        }

        log.warn("Could not resolve internal SKU for Shopee item: itemId={}, modelId={}, sku={}",
                item.itemId(), item.modelId(), item.modelSku());
        return null;
    }
}
