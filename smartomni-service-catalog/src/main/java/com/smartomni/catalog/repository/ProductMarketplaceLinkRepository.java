package com.smartomni.catalog.repository;

import com.smartomni.catalog.entity.ProductMarketplaceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface ProductMarketplaceLinkRepository extends JpaRepository<ProductMarketplaceLink, Long> {
    Optional<ProductMarketplaceLink> findBySkuIdAndPlatform(Long skuId, ProductMarketplaceLink.Platform platform);
    Optional<ProductMarketplaceLink> findByTenantIdAndSkuIdAndPlatform(Long tenantId, Long skuId, ProductMarketplaceLink.Platform platform);
    List<ProductMarketplaceLink> findBySkuId(Long skuId);
    List<ProductMarketplaceLink> findByTenantIdAndSkuIdIn(Long tenantId, List<Long> skuIds);
    Optional<ProductMarketplaceLink> findByIdAndTenantId(Long id, Long tenantId);
    Optional<ProductMarketplaceLink> findByTenantIdAndPlatformAndPlatformItemIdAndExternalSkuId(Long tenantId, ProductMarketplaceLink.Platform platform, String platformItemId, String externalSkuId);

    @Transactional
    void deleteByIdAndTenantId(Long id, Long tenantId);

    @Transactional
    void deleteByTenantIdAndSkuIdAndPlatform(Long tenantId, Long skuId, ProductMarketplaceLink.Platform platform);
}
