package com.smartomni.catalog.controller;

import com.smartomni.catalog.dto.*;
import com.smartomni.catalog.entity.Product;
import com.smartomni.catalog.entity.ProductSku;
import com.smartomni.catalog.service.ProductService;
import com.smartomni.catalog.service.UnifiedCatalogService;
import com.smartomni.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * UC-08: Quan ly san pham.
 * Omnichannel Master Catalog & Mapping (Shopee & TikTok Shop).
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final UnifiedCatalogService unifiedCatalogService;

    @GetMapping("/unified")
    public ApiResponse<Page<UnifiedProductDto>> unified(
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ApiResponse.success(unifiedCatalogService.getUnifiedCatalog(pageable, search));
    }

    @GetMapping("/mappings/unmapped")
    public ApiResponse<List<UnmappedListingDto>> unmappedListings(@RequestParam String platform) {
        return ApiResponse.success(unifiedCatalogService.getUnmappedListings(platform));
    }

    @PostMapping("/mappings/bind")
    public ApiResponse<MarketplaceMappingDto> bindMapping(@Valid @RequestBody BindSkuMappingRequest request) {
        return ApiResponse.success("Linked SKU to marketplace listing successfully", unifiedCatalogService.bindSkuMapping(request));
    }

    @PostMapping("/mappings/reconcile")
    public ApiResponse<MarketplaceMappingDto> reconcileMapping(@Valid @RequestBody ReconcileMappingRequest request) {
        return ApiResponse.success("Reconciled SKU mapping successfully", unifiedCatalogService.reconcileSkuMapping(request));
    }

    @DeleteMapping("/mappings/{linkId}")
    public ApiResponse<Void> unbindMapping(@PathVariable Long linkId) {
        unifiedCatalogService.unbindSkuMapping(linkId);
        return ApiResponse.success("Unlinked marketplace mapping successfully", null);
    }

    @GetMapping
    public ApiResponse<Page<Product>> list(
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(productService.listProducts(pageable));
    }

    @PostMapping
    public ApiResponse<Product> create(@Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.success("Tao san pham thanh cong", productService.createProduct(request));
    }

    @GetMapping("/{productId}")
    public ApiResponse<Product> getById(@PathVariable Long productId) {
        return ApiResponse.success(productService.getProductOrThrow(productId));
    }

    @GetMapping("/{productId}/skus")
    public ApiResponse<List<ProductSku>> getSkus(@PathVariable Long productId) {
        return ApiResponse.success(productService.getSkusByProduct(productId));
    }

    @GetMapping("/skus")
    public ApiResponse<List<ProductSku>> getSkusByProductIds(@RequestParam List<Long> productIds) {
        return ApiResponse.success(productService.getSkusByProductIds(productIds));
    }
}
