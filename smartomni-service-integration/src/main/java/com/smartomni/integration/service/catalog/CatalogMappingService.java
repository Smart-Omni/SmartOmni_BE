package com.smartomni.integration.service.catalog;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.entity.MarketplaceConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service ánh xạ giữa platform SKU (seller_sku / external_sku_id từ sàn TMĐT như TikTok Shop)
 * và sku_id nội bộ của SmartOmni theo tenant_id (Bước 2.1 - SKU Resolution Service).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogMappingService {

    private static final String FIND_SKU_QUERY =
            "SELECT sku_id FROM product_marketplace_links "
                    + "WHERE tenant_id = ? "
                    + "AND LOWER(platform::text) = ? "
                    + "AND (external_sku_id = ? OR seller_sku = ?) "
                    + "AND sku_id IS NOT NULL "
                    + "ORDER BY CASE WHEN external_sku_id = ? THEN 1 ELSE 2 END, id DESC "
                    + "LIMIT 1";

    private static final String FIND_IMAGE_URL_QUERY =
            "SELECT image_url FROM product_marketplace_links "
                    + "WHERE tenant_id = ? "
                    + "AND LOWER(platform::text) = ? "
                    + "AND (external_sku_id = ? OR seller_sku = ?) "
                    + "AND image_url IS NOT NULL "
                    + "ORDER BY CASE WHEN external_sku_id = ? THEN 1 ELSE 2 END, id DESC "
                    + "LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Tra cứu SkuId nội bộ theo tenantId dạng String, platform và externalSkuId.
     * Ném BusinessException (404 NOT_FOUND) và ghi log warning nếu không tìm thấy mapping.
     */
    public Long resolveInternalSkuId(String tenantId, String platform, String externalSkuId) {
        Long parsedTenantId = parseTenantId(tenantId);
        return resolveInternalSkuId(parsedTenantId, platform, externalSkuId);
    }

    /**
     * Tra cứu SkuId nội bộ theo tenantId dạng Long, platform và externalSkuId.
     * Ném BusinessException (404 NOT_FOUND) và ghi log warning nếu không tìm thấy mapping.
     */
    public Long resolveInternalSkuId(Long tenantId, String platform, String externalSkuId) {
        return findInternalSkuId(tenantId, platform, externalSkuId)
                .orElseThrow(() -> {
                    log.warn("SKU mapping not found for tenantId={}, platform={}, externalSkuId={}",
                            tenantId, platform, externalSkuId);
                    return new BusinessException(
                            String.format("Internal SKU mapping not found for externalSkuId '%s' on platform '%s' (tenant: %s)",
                                    externalSkuId, platform, tenantId),
                            HttpStatus.NOT_FOUND,
                            "SKU_NOT_MAPPED"
                    );
                });
    }

    /**
     * Overload hỗ trợ Platform enum.
     */
    public Long resolveInternalSkuId(String tenantId, MarketplaceConnection.Platform platform, String externalSkuId) {
        return resolveInternalSkuId(tenantId, platform != null ? platform.name() : null, externalSkuId);
    }

    /**
     * Overload hỗ trợ Long tenantId và Platform enum.
     */
    public Long resolveInternalSkuId(Long tenantId, MarketplaceConnection.Platform platform, String externalSkuId) {
        return resolveInternalSkuId(tenantId, platform != null ? platform.name() : null, externalSkuId);
    }

    /**
     * Tra cứu Optional SkuId nội bộ theo tenantId dạng String.
     */
    public Optional<Long> findInternalSkuId(String tenantId, String platform, String externalSkuId) {
        Long parsedTenantId = parseTenantId(tenantId);
        return findInternalSkuId(parsedTenantId, platform, externalSkuId);
    }

    /**
     * Tra cứu Optional SkuId nội bộ theo tenantId dạng Long.
     */
    public Optional<Long> findInternalSkuId(Long tenantId, String platform, String externalSkuId) {
        if (tenantId == null) {
            throw new BusinessException("tenantId is required", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (platform == null || platform.isBlank()) {
            throw new BusinessException("platform is required", HttpStatus.BAD_REQUEST, "INVALID_PLATFORM");
        }
        if (externalSkuId == null || externalSkuId.isBlank()) {
            throw new BusinessException("externalSkuId is required", HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_SKU");
        }

        String normalizedPlatform = normalizePlatform(platform);
        String trimmedSku = externalSkuId.trim();

        List<Long> results = jdbcTemplate.query(
                FIND_SKU_QUERY,
                (rs, rowNum) -> {
                    long val = rs.getLong("sku_id");
                    return rs.wasNull() ? null : val;
                },
                tenantId,
                normalizedPlatform,
                trimmedSku,
                trimmedSku,
                trimmedSku
        );

        return results.stream().filter(Objects::nonNull).findFirst();
    }

    /**
     * Tra cứu Optional imageUrl của SKU trên sàn theo tenantId dạng String.
     */
    public Optional<String> findImageUrl(String tenantId, String platform, String externalSkuId) {
        Long parsedTenantId = parseTenantId(tenantId);
        return findImageUrl(parsedTenantId, platform, externalSkuId);
    }

    /**
     * Tra cứu Optional imageUrl của SKU trên sàn theo tenantId dạng Long.
     */
    public Optional<String> findImageUrl(Long tenantId, String platform, String externalSkuId) {
        if (tenantId == null) {
            throw new BusinessException("tenantId is required", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (platform == null || platform.isBlank()) {
            throw new BusinessException("platform is required", HttpStatus.BAD_REQUEST, "INVALID_PLATFORM");
        }
        if (externalSkuId == null || externalSkuId.isBlank()) {
            throw new BusinessException("externalSkuId is required", HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_SKU");
        }

        String normalizedPlatform = normalizePlatform(platform);
        String trimmedSku = externalSkuId.trim();

        List<String> results = jdbcTemplate.query(
                FIND_IMAGE_URL_QUERY,
                (rs, rowNum) -> rs.getString("image_url"),
                tenantId,
                normalizedPlatform,
                trimmedSku,
                trimmedSku,
                trimmedSku
        );

        return results.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).findFirst();
    }

    /**
     * Overload hỗ trợ Platform enum và String tenantId.
     */
    public Optional<String> findImageUrl(String tenantId, MarketplaceConnection.Platform platform, String externalSkuId) {
        return findImageUrl(tenantId, platform != null ? platform.name() : null, externalSkuId);
    }

    /**
     * Overload hỗ trợ Platform enum và Long tenantId.
     */
    public Optional<String> findImageUrl(Long tenantId, MarketplaceConnection.Platform platform, String externalSkuId) {
        return findImageUrl(tenantId, platform != null ? platform.name() : null, externalSkuId);
    }

    private Long parseTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            try {
                return Long.parseLong(tenantId.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException("tenantId must be a numeric value: " + tenantId,
                        HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
            }
        }
        Long contextTenantId = TenantContext.getTenantId();
        if (contextTenantId != null) {
            return contextTenantId;
        }
        throw new BusinessException("tenantId is required", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
    }

    private String normalizePlatform(String platform) {
        String normalized = platform.trim().toLowerCase();
        if ("tiktok".equals(normalized) || "tiktokshop".equals(normalized) || "tiktok_shop".equals(normalized)) {
            return "tiktok_shop";
        }
        if ("shopee".equals(normalized)) {
            return "shopee";
        }
        if ("storefront".equals(normalized)) {
            return "storefront";
        }
        return normalized;
    }
}
