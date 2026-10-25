package com.smartomni.integration.service.catalog;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogMappingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ResultSet resultSet;

    private CatalogMappingService catalogMappingService;

    @BeforeEach
    void setUp() {
        catalogMappingService = new CatalogMappingService(jdbcTemplate);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("resolveInternalSkuId thành công khi SKU đã được map (external_sku_id)")
    void resolveInternalSkuId_success_whenExternalSkuMapped() {
        String tenantId = "1";
        String platform = "tiktok_shop";
        String externalSku = "TTSKU-001";
        Long expectedSkuId = 42L;

        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(1L),
                eq("tiktok_shop"),
                eq(externalSku),
                eq(externalSku),
                eq(externalSku)
        )).thenReturn(List.of(expectedSkuId));

        Long actualSkuId = catalogMappingService.resolveInternalSkuId(tenantId, platform, externalSku);

        assertNotNull(actualSkuId);
        assertEquals(expectedSkuId, actualSkuId);

        verify(jdbcTemplate).query(
                contains("SELECT sku_id FROM product_marketplace_links"),
                any(RowMapper.class),
                eq(1L),
                eq("tiktok_shop"),
                eq(externalSku),
                eq(externalSku),
                eq(externalSku)
        );
    }

    @Test
    @DisplayName("resolveInternalSkuId thành công khi ánh xạ qua seller_sku")
    void resolveInternalSkuId_success_whenSellerSkuMapped() {
        Long tenantId = 10L;
        String platform = "TIKTOK";
        String sellerSku = "SELLER-SKU-999";
        Long expectedSkuId = 105L;

        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(tenantId),
                eq("tiktok_shop"),
                eq(sellerSku),
                eq(sellerSku),
                eq(sellerSku)
        )).thenReturn(List.of(expectedSkuId));

        Long actualSkuId = catalogMappingService.resolveInternalSkuId(tenantId, platform, sellerSku);

        assertEquals(expectedSkuId, actualSkuId);
    }

    @Test
    @DisplayName("resolveInternalSkuId ném BusinessException 404 (SKU_NOT_MAPPED) khi SKU chưa được map")
    void resolveInternalSkuId_throwsNotFound_whenSkuNotMapped() {
        String tenantId = "1";
        String platform = "tiktok_shop";
        String unmappedSku = "UNMAPPED-SKU-XYZ";

        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(1L),
                eq("tiktok_shop"),
                eq(unmappedSku),
                eq(unmappedSku),
                eq(unmappedSku)
        )).thenReturn(Collections.emptyList());

        BusinessException exception = assertThrows(BusinessException.class, () ->
                catalogMappingService.resolveInternalSkuId(tenantId, platform, unmappedSku)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatus());
        assertEquals("SKU_NOT_MAPPED", exception.getErrorCode());
        assertTrue(exception.getMessage().contains(unmappedSku));
    }

    @Test
    @DisplayName("resolveInternalSkuId hoạt động chính xác với Platform enum")
    void resolveInternalSkuId_success_withPlatformEnum() {
        Long tenantId = 5L;
        String externalSku = "TTSKU-PLATFORM-ENUM";
        Long expectedSkuId = 77L;

        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(tenantId),
                eq("tiktok_shop"),
                eq(externalSku),
                eq(externalSku),
                eq(externalSku)
        )).thenReturn(List.of(expectedSkuId));

        Long actualSkuId = catalogMappingService.resolveInternalSkuId(tenantId, MarketplaceConnection.Platform.TIKTOK_SHOP, externalSku);

        assertEquals(expectedSkuId, actualSkuId);
    }

    @Test
    @DisplayName("resolveInternalSkuId fallback sang TenantContext khi tenantId null")
    void resolveInternalSkuId_fallbackToTenantContext_whenTenantIdNull() {
        TenantContext.setTenantId(99L);
        String externalSku = "SKU-FROM-CONTEXT";
        Long expectedSkuId = 888L;

        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(99L),
                eq("tiktok_shop"),
                eq(externalSku),
                eq(externalSku),
                eq(externalSku)
        )).thenReturn(List.of(expectedSkuId));

        Long actualSkuId = catalogMappingService.resolveInternalSkuId((String) null, "tiktok_shop", externalSku);

        assertEquals(expectedSkuId, actualSkuId);
    }

    @Test
    @DisplayName("resolveInternalSkuId ném BusinessException khi tenantId không hợp lệ (không phải số)")
    void resolveInternalSkuId_throwsBadRequest_whenInvalidTenantId() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                catalogMappingService.resolveInternalSkuId("invalid-tenant-abc", "tiktok_shop", "SKU-001")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("INVALID_TENANT_ID", exception.getErrorCode());
    }

    @Test
    @DisplayName("resolveInternalSkuId ném BusinessException khi tenantId không có (null và TenantContext trống)")
    void resolveInternalSkuId_throwsBadRequest_whenNoTenantId() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                catalogMappingService.resolveInternalSkuId((Long) null, "tiktok_shop", "SKU-001")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("INVALID_TENANT_ID", exception.getErrorCode());
    }

    @Test
    @DisplayName("resolveInternalSkuId ném BusinessException khi externalSkuId rỗng")
    void resolveInternalSkuId_throwsBadRequest_whenExternalSkuBlank() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                catalogMappingService.resolveInternalSkuId(1L, "tiktok_shop", "   ")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertEquals("INVALID_EXTERNAL_SKU", exception.getErrorCode());
    }

    @Test
    @DisplayName("RowMapper trích xuất chính xác sku_id và xử lý null")
    void rowMapper_extractsSkuId() throws SQLException {
        when(resultSet.getLong("sku_id")).thenReturn(500L);
        when(resultSet.wasNull()).thenReturn(false);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    RowMapper<Long> mapper = inv.getArgument(1);
                    Long mapped = mapper.mapRow(resultSet, 1);
                    return mapped != null ? List.of(mapped) : Collections.emptyList();
                });

        Long skuId = catalogMappingService.resolveInternalSkuId(1L, "tiktok_shop", "SKU-ROW-TEST");
        assertEquals(500L, skuId);
    }
}
