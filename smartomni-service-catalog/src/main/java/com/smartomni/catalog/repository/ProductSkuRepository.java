package com.smartomni.catalog.repository;

import com.smartomni.catalog.entity.ProductSku;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@org.springframework.transaction.annotation.Transactional(readOnly = true)
public interface ProductSkuRepository extends JpaRepository<ProductSku, Long> {
    boolean existsByTenantIdAndSkuCode(Long tenantId, String skuCode);
    long countByTenantId(Long tenantId);
    Optional<ProductSku> findByTenantIdAndSkuCode(Long tenantId, String skuCode); // dung cho upsert (UC-42/FR-085)
    List<ProductSku> findByProductId(Long productId);
    List<ProductSku> findByProductIdIn(List<Long> productIds);
    List<ProductSku> findByTenantIdAndProductIdIn(Long tenantId, List<Long> productIds);
}
