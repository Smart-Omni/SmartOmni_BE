package com.smartomni.catalog.service;

import com.smartomni.catalog.dto.CreateProductRequest;
import com.smartomni.catalog.entity.Product;
import com.smartomni.catalog.entity.ProductSku;
import com.smartomni.catalog.repository.ProductRepository;
import com.smartomni.catalog.repository.ProductSkuRepository;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.common.tenant.TenantPlanResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * UC-08: Quan ly san pham (Catalog Management) - FR-018,019,020
 * UC-42 (upsert logic dung chung) - FR-085
 */
@Service
public class ProductService {

    public static final int FREE_PLAN_MAX_SKUS = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "tenantId", "name", "description", "status", "createdBy", "createdAt", "updatedAt");

    private final ProductRepository productRepository;
    private final ProductSkuRepository skuRepository;
    private final TenantPlanResolver tenantPlanResolver;

    public ProductService(ProductRepository productRepository, ProductSkuRepository skuRepository) {
        this(productRepository, skuRepository, new TenantPlanResolver((javax.sql.DataSource) null));
    }

    public ProductService(ProductRepository productRepository,
                          ProductSkuRepository skuRepository,
                          TenantPlanResolver tenantPlanResolver) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.tenantPlanResolver = tenantPlanResolver != null
                ? tenantPlanResolver
                : new TenantPlanResolver((javax.sql.DataSource) null);
    }

    @Autowired
    public ProductService(ProductRepository productRepository,
                          ProductSkuRepository skuRepository,
                          ObjectProvider<TenantPlanResolver> tenantPlanResolverProvider) {
        this(productRepository, skuRepository,
                tenantPlanResolverProvider != null ? tenantPlanResolverProvider.getIfAvailable() : null);
    }

    @Transactional
    public Product createProduct(CreateProductRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("Không tìm thấy thông tin tenant trong context", HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED");
        }

        // FR-020: kiem tra gioi han SKU theo goi dich vu (Free toi da 100 SKU, Pro khong gioi han)
        assertSkuQuotaNotExceeded(tenantId, request.getSkus() != null ? request.getSkus().size() : 0);

        // FR-019: kiem tra SKU trung trong pham vi Tenant
        for (var skuReq : request.getSkus()) {
            if (skuRepository.existsByTenantIdAndSkuCode(tenantId, skuReq.getSkuCode())) {
                throw new BusinessException("SKU '" + skuReq.getSkuCode() + "' da ton tai trong he thong");
            }
        }

        Product product = new Product();
        product.setTenantId(tenantId);
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCreatedBy(TenantContext.getCurrentUserId());
        product = productRepository.save(product);

        for (var skuReq : request.getSkus()) {
            ProductSku sku = new ProductSku();
            sku.setTenantId(tenantId);
            sku.setProductId(product.getId());
            sku.setSkuCode(skuReq.getSkuCode());
            sku.setVariantName(skuReq.getVariantName());
            sku.setBasePrice(skuReq.getBasePrice());
            skuRepository.save(sku);
        }

        return product;
    }

    public Product getProductOrThrow(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("San pham khong ton tai: " + productId));
    }

    /**
     * FR-085: co che upsert theo SKU - dung boi ProductSyncService (Integration Service)
     * khi tu dong nhap san pham tu san TMDT (UC-42).
     */
    @Transactional
    public ProductSku upsertSkuFromMarketplace(Long tenantId, Long productId, String skuCode,
                                                String variantName, java.math.BigDecimal price) {
        ProductSku sku = skuRepository.findByTenantIdAndSkuCode(tenantId, skuCode)
                .orElseGet(ProductSku::new);
        sku.setTenantId(tenantId);
        sku.setProductId(productId);
        sku.setSkuCode(skuCode);
        sku.setVariantName(variantName);
        sku.setBasePrice(price);
        return skuRepository.save(sku);
    }

    public List<ProductSku> getSkusByProduct(Long productId) {
        return skuRepository.findByProductId(productId);
    }

    public List<ProductSku> getSkusByProductIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return skuRepository.findByTenantIdAndProductIdIn(tenantId, productIds);
        }
        return skuRepository.findByProductIdIn(productIds);
    }

    @Transactional(readOnly = true)
    public Page<Product> listProducts(Pageable pageable) {
        if (pageable != null && pageable.getSort().isSorted()) {
            for (var order : pageable.getSort()) {
                if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                    throw new BusinessException(
                            "Trường sắp xếp không hợp lệ: " + order.getProperty(),
                            HttpStatus.BAD_REQUEST,
                            "INVALID_SORT_PARAMETER");
                }
            }
        }
        return productRepository.findByTenantId(TenantContext.getTenantId(), pageable);
    }

    private void assertSkuQuotaNotExceeded(Long tenantId, int incomingSkus) {
        if (TenantContext.isSuperAdmin()) {
            return;
        }
        String plan = tenantPlanResolver.resolveSubscriptionPlan(tenantId);
        if ("FREE".equalsIgnoreCase(plan)) {
            long currentSkuCount = skuRepository.countByTenantId(tenantId);
            if (currentSkuCount >= FREE_PLAN_MAX_SKUS || (currentSkuCount + incomingSkus) > FREE_PLAN_MAX_SKUS) {
                throw new BusinessException("Gói Free chỉ hỗ trợ tối đa 100 SKU.", HttpStatus.BAD_REQUEST, "SKU_LIMIT_EXCEEDED");
            }
        }
    }
}
