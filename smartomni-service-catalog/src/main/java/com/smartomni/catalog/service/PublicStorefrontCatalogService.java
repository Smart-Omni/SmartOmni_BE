package com.smartomni.catalog.service;

import com.smartomni.catalog.dto.PublicStorefrontProductDto;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only catalog for anonymous storefront shoppers.
 *
 * <p>The caller must open a {@link TenantContext} scope for the store's tenant (no role) BEFORE calling, because the
 * RLS session settings are copied from the context when the transaction begins. Queries also filter tenant_id explicitly.
 *
 * <p>A product is shown only when it is active and has at least one offer: a SKU linked to a CONNECTED Shopee/TikTok shop,
 * with a price and a listing status the marketplace reports as on sale.
 */
@Service
@RequiredArgsConstructor
public class PublicStorefrontCatalogService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    /** Listing statuses meaning "on sale": ACTIVATE (TikTok import), NORMAL (Shopee import), ACTIVE (manual bind). */
    private static final String OFFER_CONDITIONS = """
            l.tenant_id = s.tenant_id
            AND l.platform::text IN ('shopee', 'tiktok_shop')
            AND l.platform_price IS NOT NULL
            AND UPPER(l.external_status) IN ('ACTIVATE', 'NORMAL', 'ACTIVE')
            AND c.status::text = 'connected'
            """;

    private static final String HAS_OFFER = """
            EXISTS (SELECT 1 FROM product_skus s
                    JOIN product_marketplace_links l ON l.sku_id = s.id
                    JOIN marketplace_connections c ON c.id = l.connection_id AND c.tenant_id = l.tenant_id
                    WHERE s.tenant_id = p.tenant_id AND s.product_id = p.id AND
            """ + OFFER_CONDITIONS + ")";

    private final JdbcTemplate jdbcTemplate;

    @Value("${smartomni.tiktok.product-url-template:}")
    private String tiktokProductUrlTemplate = "";

    @Transactional(readOnly = true)
    public Page<PublicStorefrontProductDto> listProducts(int page, int size, String search, String sort) {
        long tenantId = requireTenant();
        if (page < 0) {
            throw new BusinessException("page must be >= 0", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String orderBy = switch (sort == null || sort.isBlank() ? "newest" : sort.trim().toLowerCase(Locale.ROOT)) {
            case "newest" -> "p.id DESC";
            case "name" -> "p.name ASC, p.id ASC";
            default -> throw new BusinessException("sort must be newest or name", HttpStatus.BAD_REQUEST, "INVALID_SORT");
        };

        StringBuilder where = new StringBuilder("p.tenant_id = ? AND p.status::text = 'active' AND ").append(HAS_OFFER);
        List<Object> params = new ArrayList<>(List.of(tenantId));
        if (search != null && !search.isBlank()) {
            where.append(" AND p.name ILIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(search.trim()) + "%");
        }

        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM products p WHERE " + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageSize);
        pageParams.add((long) page * pageSize);
        List<ProductRow> rows = jdbcTemplate.query(
                "SELECT p.id, p.name, p.description FROM products p WHERE " + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
                (rs, i) -> new ProductRow(rs.getLong("id"), rs.getString("name"), rs.getString("description")),
                pageParams.toArray());

        return new PageImpl<>(assemble(tenantId, rows), PageRequest.of(page, pageSize), total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public PublicStorefrontProductDto getProduct(long productId) {
        long tenantId = requireTenant();
        List<ProductRow> rows = jdbcTemplate.query(
                "SELECT p.id, p.name, p.description FROM products p "
                        + "WHERE p.tenant_id = ? AND p.id = ? AND p.status::text = 'active' AND " + HAS_OFFER,
                (rs, i) -> new ProductRow(rs.getLong("id"), rs.getString("name"), rs.getString("description")),
                tenantId, productId);
        List<PublicStorefrontProductDto> products = assemble(tenantId, rows);
        if (products.isEmpty()) {
            throw new ResourceNotFoundException("Product not found in this store");
        }
        return products.get(0);
    }

    private List<PublicStorefrontProductDto> assemble(long tenantId, List<ProductRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = rows.stream().map(ProductRow::id).toList();
        String in = String.join(",", Collections.nCopies(productIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.addAll(productIds);

        // Offers: newest synced link first, so the first one per (sku, platform) wins.
        Map<Long, Map<Long, VariantAcc>> variantsByProduct = new HashMap<>();
        Map<Long, String> linkImageByProduct = new HashMap<>();
        jdbcTemplate.query("""
                SELECT s.id AS sku_id, s.product_id, s.variant_name,
                       l.platform::text AS platform, l.platform_price, l.currency, l.platform_item_id,
                       l.platform_product_url, l.image_url,
                       COALESCE(i.quantity_on_hand, 0) - COALESCE(i.reserved_quantity, 0) AS sellable
                FROM product_skus s
                JOIN product_marketplace_links l ON l.sku_id = s.id
                JOIN marketplace_connections c ON c.id = l.connection_id AND c.tenant_id = l.tenant_id
                LEFT JOIN inventory i ON i.sku_id = s.id AND i.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.product_id IN (""" + in + ") AND " + OFFER_CONDITIONS + """
                ORDER BY s.id, l.last_synced_at DESC NULLS LAST, l.id DESC
                """, rs -> {
            long productId = rs.getLong("product_id");
            long skuId = rs.getLong("sku_id");
            String skuName = variantName(rs.getString("variant_name"));
            VariantAcc variant = variantsByProduct
                    .computeIfAbsent(productId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(skuId, k -> new VariantAcc(skuId, skuName));
            String platform = rs.getString("platform");
            if (!variant.platforms.add(platform)) {
                return; // an older link of the same marketplace
            }
            String itemId = rs.getString("platform_item_id");
            String storedUrl = rs.getString("platform_product_url");
            String url = "shopee".equals(platform)
                    ? MarketplaceProductUrls.shopee(storedUrl, itemId)
                    : MarketplaceProductUrls.tiktok(storedUrl, itemId, tiktokProductUrlTemplate);
            String currency = rs.getString("currency");
            variant.offers.add(PublicStorefrontProductDto.Offer.builder()
                    .platform(platform)
                    .price(rs.getBigDecimal("platform_price"))
                    .currency(currency == null || currency.isBlank() ? "VND" : currency)
                    .available(rs.getLong("sellable") > 0)
                    .url(MarketplaceProductUrls.safeHttpUrl(url))
                    .build());
            String image = MarketplaceProductUrls.safeHttpUrl(rs.getString("image_url"));
            if (image != null) {
                linkImageByProduct.putIfAbsent(productId, image);
            }
        }, params.toArray());

        Map<Long, String> imageByProduct = new HashMap<>();
        jdbcTemplate.query("SELECT product_id, url FROM product_images WHERE tenant_id = ? AND product_id IN (" + in + ") "
                + "ORDER BY product_id, sort_order ASC NULLS LAST, id ASC", rs -> {
            String url = MarketplaceProductUrls.safeHttpUrl(rs.getString("url"));
            if (url != null) {
                imageByProduct.putIfAbsent(rs.getLong("product_id"), url);
            }
        }, params.toArray());

        List<PublicStorefrontProductDto> result = new ArrayList<>();
        for (ProductRow row : rows) {
            Map<Long, VariantAcc> variants = variantsByProduct.getOrDefault(row.id(), Map.of());
            if (variants.isEmpty()) {
                continue; // offers changed between the page query and this one
            }
            result.add(PublicStorefrontProductDto.builder()
                    .id(row.id())
                    .name(row.name())
                    .description(row.description())
                    .imageUrl(imageByProduct.getOrDefault(row.id(), linkImageByProduct.get(row.id())))
                    .variants(variants.values().stream()
                            .map(v -> PublicStorefrontProductDto.Variant.builder()
                                    .id(v.id).variantName(v.name).offers(List.copyOf(v.offers)).build())
                            .toList())
                    .build());
        }
        return result;
    }

    private static long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Public storefront queries need a store tenant scope");
        }
        return tenantId;
    }

    private static String variantName(String name) {
        return name == null || name.isBlank() ? "Standard" : name;
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record ProductRow(long id, String name, String description) {
    }

    private static final class VariantAcc {
        final long id;
        final String name;
        final Set<String> platforms = new HashSet<>();
        final List<PublicStorefrontProductDto.Offer> offers = new ArrayList<>();

        VariantAcc(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
