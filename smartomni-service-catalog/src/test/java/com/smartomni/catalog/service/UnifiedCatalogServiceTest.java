package com.smartomni.catalog.service;

import com.smartomni.catalog.dto.MarketplaceMappingDto;
import com.smartomni.catalog.dto.ReconcileMappingRequest;
import com.smartomni.catalog.dto.SuggestedMappingDto;
import com.smartomni.catalog.entity.Product;
import com.smartomni.catalog.entity.ProductMarketplaceLink;
import com.smartomni.catalog.entity.ProductSku;
import com.smartomni.catalog.repository.ProductMarketplaceLinkRepository;
import com.smartomni.catalog.repository.ProductRepository;
import com.smartomni.catalog.repository.ProductSkuRepository;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UnifiedCatalogServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductSkuRepository skuRepository;
    @Mock
    private ProductMarketplaceLinkRepository linkRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private UnifiedCatalogService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(42L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void computeSmartSuggestion_matchesByNameSimilarityAndPriceWithoutHardcodedSkus() {
        ProductSku sku = new ProductSku();
        sku.setId(10L);
        sku.setTenantId(42L);
        sku.setSkuCode("CUSTOM-CAM-01");
        sku.setVariantName("Pro");
        sku.setBasePrice(new BigDecimal("650000"));

        ProductMarketplaceLink shopeeLink = new ProductMarketplaceLink();
        shopeeLink.setPlatform(ProductMarketplaceLink.Platform.SHOPEE);
        shopeeLink.setPlatformPrice(new BigDecimal("650000"));

        var candidates = List.of(
                new UnifiedCatalogService.SuggestionCandidate(
                        99L, 25L, "TIKTOK_SHOP", "TT-ITEM-1", "TT-SKU-99", "TT-OTHER",
                        "Ultra HD Webcam Pro", new BigDecimal("650000"), "https://tiktok.example/TT-ITEM-1"
                ),
                new UnifiedCatalogService.SuggestionCandidate(
                        100L, 26L, "TIKTOK_SHOP", "TT-ITEM-2", "TT-SKU-100", "TT-DESK",
                        "Aluminum Headphone Stand", new BigDecimal("320000"), "https://tiktok.example/TT-ITEM-2"
                )
        );

        SuggestedMappingDto suggestion = service.computeSmartSuggestion(
                "Ultra HD Webcam", sku, shopeeLink, null, true, true, candidates);

        assertNotNull(suggestion);
        assertEquals("TIKTOK_SHOP", suggestion.getPlatform());
        assertEquals("TT-SKU-99", suggestion.getExternalSkuId());
        assertEquals(25L, suggestion.getSourceSkuId());
        assertTrue(suggestion.getConfidence() >= 70);
        assertNotNull(suggestion.getReason());
    }

    @Test
    void reconcileSkuMapping_movesLinkToTargetSkuAndHidesOrphanedProduct() {
        ProductSku targetSku = new ProductSku();
        targetSku.setId(10L);
        targetSku.setTenantId(42L);
        targetSku.setProductId(100L);
        targetSku.setBasePrice(new BigDecimal("650000"));

        ProductMarketplaceLink existingLink = new ProductMarketplaceLink();
        existingLink.setId(77L);
        existingLink.setTenantId(42L);
        existingLink.setSkuId(20L);
        existingLink.setPlatform(ProductMarketplaceLink.Platform.TIKTOK_SHOP);
        existingLink.setPlatformItemId("TT-ITEM-1");
        existingLink.setExternalSkuId("TT-SKU-99");
        existingLink.setSellerSku("TT-CAM");

        ProductSku oldSku = new ProductSku();
        oldSku.setId(20L);
        oldSku.setTenantId(42L);
        oldSku.setProductId(200L);

        Product oldProduct = new Product();
        oldProduct.setId(200L);
        oldProduct.setTenantId(42L);
        oldProduct.setStatus(Product.ProductStatus.ACTIVE);

        when(skuRepository.findById(10L)).thenReturn(Optional.of(targetSku));
        when(linkRepository.findByIdAndTenantId(77L, 42L)).thenReturn(Optional.of(existingLink));
        when(linkRepository.save(any(ProductMarketplaceLink.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skuRepository.findById(20L)).thenReturn(Optional.of(oldSku));
        when(skuRepository.findByTenantIdAndProductIdIn(42L, List.of(200L))).thenReturn(List.of(oldSku));
        when(linkRepository.findByTenantIdAndSkuIdIn(42L, List.of(20L))).thenReturn(List.of());
        when(productRepository.findById(200L)).thenReturn(Optional.of(oldProduct));
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), eq(42L), eq(10L))).thenReturn(List.of());

        ReconcileMappingRequest req = ReconcileMappingRequest.builder()
                .targetSkuId(10L)
                .platform("TIKTOK_SHOP")
                .sourceLinkId(77L)
                .build();

        MarketplaceMappingDto result = service.reconcileSkuMapping(req);

        assertEquals(10L, existingLink.getSkuId());
        assertEquals("TT-SKU-99", result.getExternalSkuId());
        assertEquals(Product.ProductStatus.HIDDEN, oldProduct.getStatus());
        verify(productRepository).save(oldProduct);
    }

    @Test
    void reconcileSkuMapping_rejectsCrossTenantTargetSku() {
        ProductSku foreignSku = new ProductSku();
        foreignSku.setId(10L);
        foreignSku.setTenantId(999L);

        when(skuRepository.findById(10L)).thenReturn(Optional.of(foreignSku));

        ReconcileMappingRequest req = ReconcileMappingRequest.builder()
                .targetSkuId(10L)
                .platform("TIKTOK_SHOP")
                .externalSkuId("TT-1")
                .build();

        assertThrows(BusinessException.class, () -> service.reconcileSkuMapping(req));
        verify(linkRepository, never()).save(any());
    }
}
