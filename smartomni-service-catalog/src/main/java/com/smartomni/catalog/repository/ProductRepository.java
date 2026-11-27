package com.smartomni.catalog.repository;

import com.smartomni.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

@org.springframework.transaction.annotation.Transactional(readOnly = true)
public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByTenantId(Long tenantId, Pageable pageable);
    Page<Product> findByTenantIdAndStatusNot(Long tenantId, Product.ProductStatus status, Pageable pageable);
    long countByTenantId(Long tenantId);
}
