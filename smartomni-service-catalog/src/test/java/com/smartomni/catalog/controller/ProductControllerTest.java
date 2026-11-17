package com.smartomni.catalog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.catalog.dto.CreateProductRequest;
import com.smartomni.catalog.entity.Product;
import com.smartomni.catalog.entity.ProductSku;
import com.smartomni.catalog.repository.ProductRepository;
import com.smartomni.catalog.repository.ProductSkuRepository;
import com.smartomni.catalog.service.ProductService;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("ProductController SKU Quota Tests")
class ProductControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private ProductRepository productRepository;
    private ProductSkuRepository skuRepository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        productRepository = mock(ProductRepository.class);
        skuRepository = mock(ProductSkuRepository.class);
        ProductService productService = new ProductService(productRepository, skuRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CreateProductRequest buildRequest(String skuCode) {
        CreateProductRequest.SkuRequest sku = new CreateProductRequest.SkuRequest();
        sku.setSkuCode(skuCode);
        sku.setVariantName("Default");
        sku.setBasePrice(new BigDecimal("199000"));

        CreateProductRequest req = new CreateProductRequest();
        req.setName("Áo Thun Basic");
        req.setDescription("Cotton 100%");
        req.setSkus(List.of(sku));
        return req;
    }

    @Test
    @DisplayName("POST /api/v1/products: Gói FREE đạt giới hạn 100 SKU trả về 400 Bad Request")
    void testCreateProduct_FreePlanExceeds100Skus_Returns400() throws Exception {
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentUserId(10L);
        TenantContext.setSubscriptionPlan("FREE");

        when(skuRepository.countByTenantId(1L)).thenReturn(100L);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("SKU-101"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Gói Free chỉ hỗ trợ tối đa 100 SKU."));

        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /api/v1/products: Gói FREE dưới 100 SKU tạo thành công")
    void testCreateProduct_FreePlanUnder100Skus_Success() throws Exception {
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentUserId(10L);
        TenantContext.setSubscriptionPlan("FREE");

        when(skuRepository.countByTenantId(1L)).thenReturn(99L);
        when(skuRepository.existsByTenantIdAndSkuCode(1L, "SKU-100")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(55L);
            return p;
        });
        when(skuRepository.save(any(ProductSku.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("SKU-100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(55));
    }

    @Test
    @DisplayName("POST /api/v1/products: Gói PRO có >= 100 SKU vẫn tạo sản phẩm thành công")
    void testCreateProduct_ProPlanOver100Skus_Success() throws Exception {
        TenantContext.setTenantId(2L);
        TenantContext.setCurrentUserId(20L);
        TenantContext.setSubscriptionPlan("PRO");

        when(skuRepository.countByTenantId(2L)).thenReturn(500L);
        when(skuRepository.existsByTenantIdAndSkuCode(2L, "SKU-501")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(88L);
            return p;
        });
        when(skuRepository.save(any(ProductSku.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("SKU-501"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(88));
    }

    @Test
    void getSkusByProductIdsBatch() throws Exception {
        TenantContext.setTenantId(1L);
        ProductSku sku1 = new ProductSku();
        sku1.setId(10L);
        sku1.setProductId(1L);
        sku1.setSkuCode("SKU-P1-01");

        ProductSku sku2 = new ProductSku();
        sku2.setId(20L);
        sku2.setProductId(2L);
        sku2.setSkuCode("SKU-P2-01");

        when(skuRepository.findByTenantIdAndProductIdIn(1L, List.of(1L, 2L))).thenReturn(List.of(sku1, sku2));

        mockMvc.perform(get("/api/v1/products/skus?productIds=1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].skuCode").value("SKU-P1-01"))
                .andExpect(jsonPath("$.data[1].skuCode").value("SKU-P2-01"));
    }

    @Test
    @DisplayName("GET /api/v1/products?page=0&size=20 trả về 200 OK")
    void listProducts_DefaultPagination_Returns200() throws Exception {
        TenantContext.setTenantId(1L);
        Product p = new Product();
        p.setId(1L);
        p.setName("Áo Thun");
        when(productRepository.findByTenantId(eq(1L), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(p), inv.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/products?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/products?page=0&size=20&sort=id,desc trả về 200 OK")
    void listProducts_ValidSort_Returns200() throws Exception {
        TenantContext.setTenantId(1L);
        Product p = new Product();
        p.setId(2L);
        p.setName("Quần Jean");
        when(productRepository.findByTenantId(eq(1L), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(p), inv.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/products?page=0&size=20&sort=id,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/products?page=0&size=20&sort=string trả về 400 INVALID_SORT_PARAMETER")
    void listProducts_InvalidSort_Returns400() throws Exception {
        TenantContext.setTenantId(1L);

        mockMvc.perform(get("/api/v1/products?page=0&size=20&sort=string"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Trường sắp xếp không hợp lệ: string"));

        verify(productRepository, never()).findByTenantId(any(), any());
    }
}

