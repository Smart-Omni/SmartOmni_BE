package com.smartomni.integration.service.catalog;

import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeItemDetail;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient.ShopeeModelDetail;
import com.smartomni.integration.dto.MarketplaceProductDto;
import com.smartomni.integration.dto.MarketplaceProductDto.VariantDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps Shopee item details and model variations into canonical {@link MarketplaceProductDto}.
 */
public final class ShopeeProductMapper {

    private ShopeeProductMapper() {
    }

    public static MarketplaceProductDto toDto(ShopeeItemDetail item, List<ShopeeModelDetail> models) {
        MarketplaceProductDto dto = new MarketplaceProductDto();
        dto.setPlatformItemId(String.valueOf(item.itemId()));
        dto.setPlatformStatus(item.itemStatus());
        dto.setName(item.itemName());
        dto.setDescription(item.description());
        if (item.imageUrl() != null && !item.imageUrl().isBlank()) {
            dto.setImageUrls(List.of(item.imageUrl()));
        }

        List<VariantDto> variants = new ArrayList<>();
        if (!item.hasModel() || models == null || models.isEmpty()) {
            // Single variant product
            VariantDto v = new VariantDto();
            v.setExternalSkuId(String.valueOf(item.itemId()));
            v.setSellerSku(item.itemSku() != null && !item.itemSku().isBlank() ? item.itemSku().trim() : null);
            v.setCurrency(item.currency() != null ? item.currency() : "VND");
            v.setPrice(item.currentPrice());
            v.setVariantName("Default");
            variants.add(v);
        } else {
            // 2-Tier or multi-model variations
            for (ShopeeModelDetail m : models) {
                VariantDto v = new VariantDto();
                v.setExternalSkuId(String.valueOf(m.modelId()));
                v.setSellerSku(m.modelSku() != null && !m.modelSku().isBlank() ? m.modelSku().trim() : null);
                v.setCurrency(m.currency() != null ? m.currency() : "VND");
                v.setPrice(m.currentPrice());
                v.setVariantName(m.modelName() != null && !m.modelName().isBlank() ? m.modelName() : "Model " + m.modelId());
                variants.add(v);
            }
        }
        dto.setVariants(variants);
        return dto;
    }
}
