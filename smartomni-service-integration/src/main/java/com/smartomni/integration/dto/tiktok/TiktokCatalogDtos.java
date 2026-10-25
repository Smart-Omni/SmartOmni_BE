package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed views of the TikTok Shop catalog responses, limited to the fields SmartOmni uses. Shapes come from the
 * official OAS snapshots in {@code contracts/snapshots/oas} of the standalone simulator repo
 * ({@code product.search}, {@code product.detail}, {@code logistics.warehouses}); unknown fields are ignored so
 * the inventory fields (warehouse id, quantity) are kept without freezing the whole schema.
 * Amounts are STRINGS in the source schema and are parsed with {@link #parseAmount(String)}.
 */
public final class TiktokCatalogDtos {

    private TiktokCatalogDtos() {
    }

    /** Parses a platform amount string. Returns null for null, blank, non-numeric or negative values (never 0 as a fallback). */
    public static BigDecimal parseAmount(String amount) {
        if (amount == null || amount.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(amount.trim());
            return value.signum() < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductPage(@JsonProperty("next_page_token") String nextPageToken,
                              @JsonProperty("total_count") Integer totalCount,
                              List<Product> products) {
        public ProductPage {
            products = products == null ? List.of() : products;
            nextPageToken = nextPageToken == null ? "" : nextPageToken;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Product(String id, String title, String status, String description,
                          @JsonProperty("main_images") List<Image> mainImages, List<Sku> skus) {
        public Product {
            skus = skus == null ? List.of() : skus;
            mainImages = mainImages == null ? List.of() : mainImages;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(String uri, List<String> urls) {
        public Image {
            urls = urls == null ? List.of() : urls;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sku(String id, @JsonProperty("seller_sku") String sellerSku, Price price,
                      List<Inventory> inventory, @JsonProperty("status_info") StatusInfo statusInfo,
                      @JsonProperty("sales_attributes") List<SalesAttribute> salesAttributes) {
        public Sku {
            inventory = inventory == null ? List.of() : inventory;
            salesAttributes = salesAttributes == null ? List.of() : salesAttributes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Price(String currency, @JsonProperty("sale_price") String salePrice,
                        @JsonProperty("tax_exclusive_price") String taxExclusivePrice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inventory(@JsonProperty("warehouse_id") String warehouseId, Integer quantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusInfo(String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SalesAttribute(String name, @JsonProperty("value_name") String valueName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WarehouseList(List<Warehouse> warehouses) {
        public WarehouseList {
            warehouses = warehouses == null ? List.of() : warehouses;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Warehouse(String id, String name, String type, @JsonProperty("sub_type") String subType,
                            @JsonProperty("effect_status") String effectStatus,
                            @JsonProperty("is_default") Boolean isDefault, Address address) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String city, @JsonProperty("region_code") String regionCode) {
    }
}
