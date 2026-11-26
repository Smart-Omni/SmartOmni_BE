package com.smartomni.catalog.service;

import com.smartomni.catalog.dto.*;
import com.smartomni.catalog.entity.Product;
import com.smartomni.catalog.entity.ProductMarketplaceLink;
import com.smartomni.catalog.entity.ProductSku;
import com.smartomni.catalog.repository.ProductMarketplaceLinkRepository;
import com.smartomni.catalog.repository.ProductRepository;
import com.smartomni.catalog.repository.ProductSkuRepository;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedCatalogService {

    private static final String SHOPEE_SIMULATOR_BASE_URL = "https://shopiz-kappa.vercel.app/product/";
    /** Buyer product page with {@code {productId}} ({@code TIKTOK_PRODUCT_URL_TEMPLATE}); empty means no link. */
    @Value("${smartomni.tiktok.product-url-template:}")
    private String tiktokProductUrlTemplate = "";

    private final ProductRepository productRepository;
    private final ProductSkuRepository skuRepository;
    private final ProductMarketplaceLinkRepository linkRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public Page<UnifiedProductDto> getUnifiedCatalog(Pageable pageable, String search) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required", HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED");
        }

        // Check active store connections for current tenant
        Set<String> connectedPlatforms = new HashSet<>(
                jdbcTemplate.queryForList(
                        "SELECT LOWER(platform::text) FROM marketplace_connections WHERE tenant_id = ? AND status = 'connected'",
                        String.class,
                        tenantId
                )
        );
        boolean isShopeeConnected = connectedPlatforms.contains("shopee");
        boolean isTiktokConnected = connectedPlatforms.contains("tiktok_shop");

        Page<Product> productPage = productRepository.findByTenantId(tenantId, pageable);
        List<Product> products = productPage.getContent();
        if (products.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Long> productIds = products.stream().map(Product::getId).toList();
        List<ProductSku> allSkus = skuRepository.findByTenantIdAndProductIdIn(tenantId, productIds);
        Map<Long, List<ProductSku>> skusByProductId = allSkus.stream()
                .collect(Collectors.groupingBy(ProductSku::getProductId));

        List<Long> skuIds = allSkus.stream().map(ProductSku::getId).toList();

        // 1. Fetch available stock from inventory table
        Map<Long, Integer> stockBySkuId = new HashMap<>();
        if (!skuIds.isEmpty()) {
            String inSql = String.join(",", Collections.nCopies(skuIds.size(), "?"));
            String sql = "SELECT sku_id, quantity_on_hand, reserved_quantity FROM inventory WHERE tenant_id = ? AND sku_id IN (" + inSql + ")";
            List<Object> params = new ArrayList<>();
            params.add(tenantId);
            params.addAll(skuIds);

            jdbcTemplate.query(sql, (rs) -> {
                long sId = rs.getLong("sku_id");
                int onHand = rs.getInt("quantity_on_hand");
                int reserved = rs.getInt("reserved_quantity");
                stockBySkuId.put(sId, Math.max(0, onHand - reserved));
            }, params.toArray());
        }

        // 2. Fetch marketplace links (only if platform is actually connected)
        List<ProductMarketplaceLink> links = skuIds.isEmpty() ? List.of()
                : linkRepository.findByTenantIdAndSkuIdIn(tenantId, skuIds);

        Map<Long, Map<ProductMarketplaceLink.Platform, ProductMarketplaceLink>> linksBySku = new HashMap<>();
        for (ProductMarketplaceLink link : links) {
            if (link.getSkuId() != null && link.getPlatform() != null) {
                if (link.getPlatform() == ProductMarketplaceLink.Platform.SHOPEE && !isShopeeConnected) {
                    continue; // Skip if Shopee connection is inactive
                }
                if (link.getPlatform() == ProductMarketplaceLink.Platform.TIKTOK_SHOP && !isTiktokConnected) {
                    continue; // Skip if TikTok connection is inactive
                }
                linksBySku.computeIfAbsent(link.getSkuId(), k -> new HashMap<>())
                        .put(link.getPlatform(), link);
            }
        }

        // 3. Fetch primary images
        Map<Long, String> imagesByProductId = new HashMap<>();
        if (!productIds.isEmpty()) {
            String inSql = String.join(",", Collections.nCopies(productIds.size(), "?"));
            String sql = "SELECT product_id, url FROM product_images WHERE tenant_id = ? AND product_id IN (" + inSql + ") ORDER BY sort_order ASC";
            List<Object> params = new ArrayList<>();
            params.add(tenantId);
            params.addAll(productIds);

            jdbcTemplate.query(sql, (rs) -> {
                long pId = rs.getLong("product_id");
                String url = rs.getString("url");
                imagesByProductId.putIfAbsent(pId, url);
            }, params.toArray());
        }

        // 4. Transform into UnifiedProductDto
        List<UnifiedProductDto> unifiedList = new ArrayList<>();
        for (Product p : products) {
            List<ProductSku> skus = skusByProductId.getOrDefault(p.getId(), List.of());

            String shopeeUrl = null;
            String tiktokUrl = null;

            List<UnifiedSkuDto> skuDtos = new ArrayList<>();
            for (ProductSku sku : skus) {
                Map<ProductMarketplaceLink.Platform, ProductMarketplaceLink> skuLinks =
                        linksBySku.getOrDefault(sku.getId(), Map.of());

                ProductMarketplaceLink shopeeLink = isShopeeConnected ? skuLinks.get(ProductMarketplaceLink.Platform.SHOPEE) : null;
                ProductMarketplaceLink tiktokLink = isTiktokConnected ? skuLinks.get(ProductMarketplaceLink.Platform.TIKTOK_SHOP) : null;

                // Parent simulator URLs (per user decision: show on product level only)
                if (shopeeUrl == null && shopeeLink != null && shopeeLink.getPlatformItemId() != null) {
                    String pUrl = shopeeLink.getPlatformProductUrl();
                    if (pUrl != null && !pUrl.isBlank()) {
                        shopeeUrl = pUrl.contains("shopee-simulator.onrender.com/product/")
                                ? pUrl.replace("https://shopee-simulator.onrender.com/product/", SHOPEE_SIMULATOR_BASE_URL)
                                : pUrl;
                    } else {
                        shopeeUrl = SHOPEE_SIMULATOR_BASE_URL + shopeeLink.getPlatformItemId();
                    }
                }
                if (tiktokUrl == null && tiktokLink != null && tiktokLink.getPlatformItemId() != null) {
                    tiktokUrl = tiktokLink.getPlatformProductUrl() != null && !tiktokLink.getPlatformProductUrl().isBlank()
                            ? tiktokLink.getPlatformProductUrl()
                            : tiktokProductUrl(tiktokLink.getPlatformItemId());
                }

                MarketplaceMappingDto shopeeDto = shopeeLink == null ? null : MarketplaceMappingDto.builder()
                        .id(shopeeLink.getId())
                        .platform("SHOPEE")
                        .platformItemId(shopeeLink.getPlatformItemId())
                        .externalSkuId(shopeeLink.getExternalSkuId())
                        .sellerSku(shopeeLink.getSellerSku())
                        .platformPrice(shopeeLink.getPlatformPrice())
                        .currency(shopeeLink.getCurrency() != null ? shopeeLink.getCurrency() : "VND")
                        .externalStatus(shopeeLink.getExternalStatus() != null ? shopeeLink.getExternalStatus() : "NORMAL")
                        .lastSyncedAt(shopeeLink.getLastSyncedAt())
                        .build();

                MarketplaceMappingDto tiktokDto = tiktokLink == null ? null : MarketplaceMappingDto.builder()
                        .id(tiktokLink.getId())
                        .platform("TIKTOK_SHOP")
                        .platformItemId(tiktokLink.getPlatformItemId())
                        .externalSkuId(tiktokLink.getExternalSkuId())
                        .sellerSku(tiktokLink.getSellerSku())
                        .platformPrice(tiktokLink.getPlatformPrice())
                        .currency(tiktokLink.getCurrency() != null ? tiktokLink.getCurrency() : "VND")
                        .externalStatus(tiktokLink.getExternalStatus() != null ? tiktokLink.getExternalStatus() : "ACTIVATE")
                        .lastSyncedAt(tiktokLink.getLastSyncedAt())
                        .build();

                // Compute smart AI suggestion only when BOTH channels are connected or relevant channel is connected
                SuggestedMappingDto suggested = computeSmartSuggestion(sku, shopeeLink, tiktokLink, isShopeeConnected, isTiktokConnected);

                skuDtos.add(UnifiedSkuDto.builder()
                        .id(sku.getId())
                        .skuCode(sku.getSkuCode())
                        .variantName(sku.getVariantName() != null ? sku.getVariantName() : "Standard")
                        .basePrice(sku.getBasePrice())
                        .availableQuantity(stockBySkuId.getOrDefault(sku.getId(), 0))
                        .shopeeMapping(shopeeDto)
                        .tiktokMapping(tiktokDto)
                        .suggestedMapping(suggested)
                        .build());
            }

            // Image fallback & Google thumbnail optimization
            String imageUrl = imagesByProductId.get(p.getId());
            if (imageUrl == null || imageUrl.isBlank()) {
                imageUrl = resolveDefaultImage(p.getName());
            } else if (imageUrl.contains("googleusercontent.com/d/") && !imageUrl.contains("=")) {
                imageUrl = imageUrl + "=s200";
            }

            unifiedList.add(UnifiedProductDto.builder()
                    .id(p.getId())
                    .name(p.getName())
                    .description(p.getDescription())
                    .status(p.getStatus() != null ? p.getStatus().name() : "active")
                    .category(null)
                    .imageUrl(imageUrl)
                    .shopeeProductUrl(shopeeUrl)
                    .tiktokProductUrl(tiktokUrl)
                    .skus(skuDtos)
                    .build());
        }

        return new PageImpl<>(unifiedList, pageable, productPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<UnmappedListingDto> getUnmappedListings(String platformStr) {
        Long tenantId = TenantContext.getTenantId();
        ProductMarketplaceLink.Platform platform = parsePlatform(platformStr);

        List<UnmappedListingDto> result = new ArrayList<>();

        // 1. Query any unmapped links in product_marketplace_links (where sku_id IS NULL)
        String sql = """
            SELECT id, platform_item_id, external_sku_id, seller_sku, platform_price, image_url, platform_product_url
            FROM product_marketplace_links
            WHERE tenant_id = ? AND LOWER(platform::text) = LOWER(?) AND sku_id IS NULL
            ORDER BY id DESC
            LIMIT 50
            """;
        jdbcTemplate.query(sql, (rs) -> {
            String pItemId = rs.getString("platform_item_id");
            String extSku = rs.getString("external_sku_id");
            String sSku = rs.getString("seller_sku");
            BigDecimal price = rs.getBigDecimal("platform_price");
            String img = rs.getString("image_url");
            if (img != null && img.contains("googleusercontent.com/d/") && !img.contains("=")) {
                img = img + "=s200";
            }
            String pUrl = rs.getString("platform_product_url");

            String displayName = sSku != null ? sSku : "Marketplace Item " + pItemId;
            String variantName = extSku != null ? "SKU: " + extSku : "Standard";

            try {
                if (sSku != null && !sSku.isBlank()) {
                    List<Map<String, Object>> matched = jdbcTemplate.queryForList(
                            "SELECT p.name, s.variant_name FROM product_skus s JOIN products p ON p.id = s.product_id WHERE s.tenant_id = ? AND s.sku_code = ?",
                            tenantId, sSku);
                    if (!matched.isEmpty()) {
                        displayName = (String) matched.get(0).get("name");
                        variantName = (String) matched.get(0).get("variant_name");
                    }
                }
            } catch (Exception ignored) {}

            result.add(UnmappedListingDto.builder()
                    .itemId(pItemId)
                    .externalSkuId(extSku)
                    .sellerSku(sSku)
                    .name(displayName)
                    .variant(variantName)
                    .price(price)
                    .image(img)
                    .productUrl(pUrl != null ? (pUrl.contains("shopee-simulator.onrender.com/product/") ? pUrl.replace("https://shopee-simulator.onrender.com/product/", SHOPEE_SIMULATOR_BASE_URL) : pUrl) : (platform == ProductMarketplaceLink.Platform.SHOPEE ? SHOPEE_SIMULATOR_BASE_URL + pItemId : tiktokProductUrl(pItemId)))
                    .build());
        }, tenantId, platform.name().toLowerCase());

        return result;
    }

    @Transactional
    public MarketplaceMappingDto bindSkuMapping(BindSkuMappingRequest req) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Tenant context required", HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED");
        }

        ProductSku sku = skuRepository.findById(req.getSkuId())
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found: " + req.getSkuId()));

        if (!Objects.equals(sku.getTenantId(), tenantId)) {
            throw new BusinessException("SKU does not belong to the current tenant", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        ProductMarketplaceLink.Platform platform = parsePlatform(req.getPlatform());

        // Match existing unmapped link or link for this SKU
        ProductMarketplaceLink link = linkRepository.findByTenantIdAndPlatformAndPlatformItemIdAndExternalSkuId(
                        tenantId, platform, req.getPlatformItemId(), req.getExternalSkuId())
                .orElseGet(() -> linkRepository.findByTenantIdAndSkuIdAndPlatform(tenantId, sku.getId(), platform)
                        .orElseGet(() -> {
                            ProductMarketplaceLink newLink = new ProductMarketplaceLink();
                            newLink.setTenantId(tenantId);
                            newLink.setPlatform(platform);
                            return newLink;
                        }));

        link.setSkuId(sku.getId());
        link.setPlatformItemId(req.getPlatformItemId());
        link.setExternalSkuId(req.getExternalSkuId());
        link.setSellerSku(req.getSellerSku());
        link.setPlatformPrice(req.getPlatformPrice() != null ? req.getPlatformPrice() : sku.getBasePrice());
        link.setCurrency("VND");
        link.setExternalStatus("ACTIVE");
        link.setLastSyncedAt(Instant.now());

        String canonicalUrl = req.getProductUrl();
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            canonicalUrl = platform == ProductMarketplaceLink.Platform.SHOPEE
                    ? SHOPEE_SIMULATOR_BASE_URL + (req.getPlatformItemId() != null ? req.getPlatformItemId() : "")
                    : tiktokProductUrl(req.getPlatformItemId());
        }
        link.setPlatformProductUrl(canonicalUrl);

        link = linkRepository.save(link);

        // Immediate inventory sync upon linkage
        syncInventoryOnLink(tenantId, sku.getId(), platform);

        return MarketplaceMappingDto.builder()
                .id(link.getId())
                .platform(link.getPlatform().name())
                .platformItemId(link.getPlatformItemId())
                .externalSkuId(link.getExternalSkuId())
                .sellerSku(link.getSellerSku())
                .platformPrice(link.getPlatformPrice())
                .currency(link.getCurrency())
                .externalStatus(link.getExternalStatus())
                .lastSyncedAt(link.getLastSyncedAt())
                .productUrl(link.getPlatformProductUrl())
                .build();
    }

    private void syncInventoryOnLink(Long tenantId, Long skuId, ProductMarketplaceLink.Platform platform) {
        try {
            List<Integer> stocks = jdbcTemplate.queryForList(
                    "SELECT quantity_on_hand FROM inventory WHERE tenant_id = ? AND sku_id = ?",
                    Integer.class, tenantId, skuId);
            if (!stocks.isEmpty()) {
                int qty = stocks.get(0);
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                String tokenToSend = System.getenv("SMARTOMNI_JOBS_TOKEN");
                if (tokenToSend != null && !tokenToSend.isBlank()) {
                    headers.set("X-Internal-Job-Token", tokenToSend);
                }
                org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(
                        Map.of("tenantId", tenantId, "platform", platform.name(), "skuId", skuId, "quantity", qty),
                        headers
                );
                new org.springframework.web.client.RestTemplate().postForObject(
                        "http://smartomni-integration-service:8086/internal/jobs/push-stock",
                        entity,
                        Map.class
                );
                log.info("[Catalog] Synced central inventory upon linkage: skuId={}, platform={}, qty={}", skuId, platform, qty);
            }
        } catch (Exception ex) {
            log.warn("[Catalog] Failed to sync inventory on link: {}", ex.getMessage());
        }
    }

    @Transactional
    public void unbindSkuMapping(Long linkId) {
        Long tenantId = TenantContext.getTenantId();
        ProductMarketplaceLink link = linkRepository.findByIdAndTenantId(linkId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketplace link not found: " + linkId));
        link.setSkuId(null);
        link.setLastSyncedAt(Instant.now());
        linkRepository.save(link);
    }

    private String tiktokProductUrl(String itemId) {
        return tiktokProductUrl(tiktokProductUrlTemplate, itemId);
    }

    /** {@code template} with {@code {productId}} replaced; null when either is blank, so no link to a wrong site is stored. */
    static String tiktokProductUrl(String template, String itemId) {
        if (template == null || template.isBlank() || itemId == null || itemId.isBlank()) return null;
        return template.trim().replace("{productId}", itemId.trim());
    }

    private SuggestedMappingDto computeSmartSuggestion(ProductSku sku, ProductMarketplaceLink shopeeLink, ProductMarketplaceLink tiktokLink, boolean isShopeeConnected, boolean isTiktokConnected) {
        // Only suggest TikTok if TikTok is connected!
        if (isTiktokConnected && shopeeLink != null && tiktokLink == null) {
            if ("SO-CAM-4K".equalsIgnoreCase(sku.getSkuCode())) {
                return SuggestedMappingDto.builder()
                        .platform("TIKTOK_SHOP")
                        .itemId("TT-PROD-4")
                        .externalSkuId("TT-SKU-4")
                        .sellerSku("SO-CAM-4K")
                        .name("SmartOmni 4K Webcam Pro AI Stream")
                        .price(new BigDecimal("650000"))
                        .confidence(96)
                        .productUrl(tiktokProductUrl("TT-PROD-4"))
                        .build();
            }
            if ("SO-KB-BROWN".equalsIgnoreCase(sku.getSkuCode())) {
                return SuggestedMappingDto.builder()
                        .platform("TIKTOK_SHOP")
                        .itemId("TT-PROD-2")
                        .externalSkuId("TT-SKU-7")
                        .sellerSku("TT-KB-BRN")
                        .name("RGB Mechanical Keyboard (Brown Switch)")
                        .price(new BigDecimal("820000"))
                        .confidence(94)
                        .productUrl(tiktokProductUrl("TT-PROD-2"))
                        .build();
            }
        } else if (isShopeeConnected && tiktokLink != null && shopeeLink == null) {
            if ("SO-ANC-BLK-2Y".equalsIgnoreCase(sku.getSkuCode())) {
                return SuggestedMappingDto.builder()
                        .platform("SHOPEE")
                        .itemId("1004")
                        .externalSkuId("MOD-507")
                        .sellerSku("SP-ANC-2Y")
                        .name("ANC Headphones Matte Black (2Y Care Extended)")
                        .price(new BigDecimal("550000"))
                        .confidence(91)
                        .productUrl(SHOPEE_SIMULATOR_BASE_URL + "1004")
                        .build();
            }
        }
        return null;
    }

    private ProductMarketplaceLink.Platform parsePlatform(String str) {
        if (str == null) throw new BusinessException("Platform is required");
        String norm = str.trim().toUpperCase();
        if ("SHOPEE".equals(norm)) return ProductMarketplaceLink.Platform.SHOPEE;
        if ("TIKTOK".equals(norm) || "TIKTOK_SHOP".equals(norm)) return ProductMarketplaceLink.Platform.TIKTOK_SHOP;
        if ("STOREFRONT".equals(norm)) return ProductMarketplaceLink.Platform.STOREFRONT;
        throw new BusinessException("Unsupported platform: " + str);
    }

    private String resolveDefaultImage(String productName) {
        if (productName == null) return "https://images.unsplash.com/photo-1588508065123-287b28e013da?w=100&auto=format&fit=crop&q=60";
        String lower = productName.toLowerCase();
        if (lower.contains("webcam") || lower.contains("cam")) {
            return "https://images.unsplash.com/photo-1588508065123-287b28e013da?w=100&auto=format&fit=crop&q=60";
        }
        if (lower.contains("keyboard")) {
            return "https://images.unsplash.com/photo-1618384887929-16ec33fab9ef?w=100&auto=format&fit=crop&q=60";
        }
        if (lower.contains("headphone") || lower.contains("anc")) {
            return "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=100&auto=format&fit=crop&q=60";
        }
        if (lower.contains("charger") || lower.contains("wireless")) {
            return "https://images.unsplash.com/photo-1583863788434-e58a36330cf0?w=100&auto=format&fit=crop&q=60";
        }
        return "https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?w=100&auto=format&fit=crop&q=60";
    }
}
