package com.smartomni.catalog.controller;

import com.smartomni.catalog.dto.CreateProductRequest;
import com.smartomni.catalog.entity.Product;
import com.smartomni.catalog.entity.ProductSku;
import com.smartomni.catalog.service.ProductService;
import com.smartomni.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** UC-08: Quan ly san pham. UC-09/UC-15/UC-16 tuong ung se co controller rieng (Marketplace link, Image, Promotion). */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<Page<Product>> list(Pageable pageable) {
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
