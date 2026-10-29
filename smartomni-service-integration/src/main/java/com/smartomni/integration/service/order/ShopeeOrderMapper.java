package com.smartomni.integration.service.order;

import com.smartomni.integration.client.shopee.ShopeeOrderClient;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.OrderItemDetail;
import com.smartomni.integration.client.shopee.ShopeeOrderClient.ShopeeOrderDetail;
import com.smartomni.integration.dto.order.ShopeeOrderPayload;
import com.smartomni.integration.dto.order.ShopeeOrderPayload.OrderItemPayload;
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

    /**
     * Maps Shopee status to SmartOmni canonical OrderStatus string:
     * UNPAID -> PENDING
     * READY_TO_SHIP, PROCESSED -> TO_SHIP
     * SHIPPED -> SHIPPED
     * TO_CONFIRM_RECEIVE -> DELIVERING
     * COMPLETED -> COMPLETED
     * IN_CANCEL, CANCELLED -> CANCELLED
     * TO_RETURN -> RETURNED
     */
    public String toCanonicalStatus(String shopeeStatus) {
        if (shopeeStatus == null) return "PENDING";
        return switch (shopeeStatus.toUpperCase()) {
            case "UNPAID" -> "PENDING";
            case "READY_TO_SHIP", "PROCESSED" -> "TO_SHIP";
            case "SHIPPED" -> "SHIPPED";
            case "TO_CONFIRM_RECEIVE" -> "DELIVERING";
            case "COMPLETED" -> "COMPLETED";
            case "IN_CANCEL", "CANCELLED" -> "CANCELLED";
            case "TO_RETURN" -> "RETURNED";
            default -> "PENDING";
        };
    }

    /**
     * Converts ShopeeOrderDetail into normalized ShopeeOrderPayload resolving internal SKU IDs.
     */
    public ShopeeOrderPayload toPayload(Long tenantId, Long connectionId, ShopeeOrderDetail order) {
        String canonicalStatus = toCanonicalStatus(order.orderStatus());

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

        List<OrderItemPayload> itemPayloads = new ArrayList<>();
        if (order.items() != null) {
            for (OrderItemDetail item : order.items()) {
                Long resolvedSkuId = resolveSkuId(connectionId, item);

                String skuCode = (item.modelSku() != null && !item.modelSku().isBlank())
                        ? item.modelSku()
                        : item.itemSku();

                String externalSkuId = String.valueOf(item.modelId() != 0 ? item.modelId() : item.itemId());

                itemPayloads.add(OrderItemPayload.builder()
                        .skuId(resolvedSkuId)
                        .skuCode(skuCode)
                        .externalSkuId(externalSkuId)
                        .productName(item.itemName() + (item.modelName() != null && !item.modelName().isBlank() ? " - " + item.modelName() : ""))
                        .quantity(item.quantityPurchased())
                        .unitPrice(item.discountedPrice())
                        .build());
            }
        }

        return ShopeeOrderPayload.builder()
                .tenantId(tenantId)
                .platform("SHOPEE")
                .platformOrderId(order.orderSn())
                .status(canonicalStatus)
                .rawShopeeStatus(order.orderStatus())
                .customerName(customerName)
                .customerPhone(customerPhone)
                .shippingAddress(fullAddress)
                .totalAmount(order.totalAmount())
                .currency(order.currency())
                .shipByDate(order.shipByDate())
                .createTime(order.createTime())
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
