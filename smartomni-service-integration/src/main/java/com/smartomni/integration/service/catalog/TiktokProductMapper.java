package com.smartomni.integration.service.catalog;

import com.smartomni.integration.dto.MarketplaceProductDto;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Product;
import com.smartomni.integration.dto.tiktok.TiktokCatalogDtos.Sku;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** TikTok catalog JSON (typed) to SmartOmni's platform-neutral product; keeps platform ids, seller SKU, currency and per-warehouse stock. */
public final class TiktokProductMapper {

    private TiktokProductMapper() {
    }

    public static MarketplaceProductDto toNeutral(Product product, String fallbackStatus) {
        MarketplaceProductDto dto = new MarketplaceProductDto();
        dto.setPlatformItemId(product.id());
        dto.setName(product.title());
        dto.setDescription(product.description());
        dto.setPlatformStatus(product.status() != null ? product.status() : fallbackStatus);
        dto.setImageUrls(product.mainImages().stream().flatMap(i -> i.urls().stream()).toList());
        List<MarketplaceProductDto.VariantDto> variants = new ArrayList<>();
        for (Sku sku : product.skus()) {
            MarketplaceProductDto.VariantDto v = new MarketplaceProductDto.VariantDto();
            v.setExternalSkuId(sku.id());
            String sellerSku = sku.sellerSku() == null ? null : sku.sellerSku().trim();
            v.setSellerSku(sellerSku == null || sellerSku.isEmpty() ? null : sellerSku);
            v.setSkuCode(v.getSellerSku());
            v.setVariantName(variantName(sku));
            if (sku.price() != null) {
                v.setCurrency(sku.price().currency());
                v.setPrice(TiktokCatalogDtos.parseAmount(sku.price().salePrice()));
            }
            v.setStock(sku.inventory().stream().map(i -> {
                MarketplaceProductDto.StockDto s = new MarketplaceProductDto.StockDto();
                s.setWarehouseId(i.warehouseId());
                s.setQuantity(i.quantity());
                return s;
            }).toList());
            variants.add(v);
        }
        dto.setVariants(variants);
        return dto;
    }

    private static String variantName(Sku sku) {
        String joined = sku.salesAttributes().stream().map(a -> a.valueName()).filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(" / "));
        return joined.isEmpty() ? sku.sellerSku() : joined;
    }
}
