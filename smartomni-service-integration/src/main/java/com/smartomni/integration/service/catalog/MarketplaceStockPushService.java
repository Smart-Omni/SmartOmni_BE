package com.smartomni.integration.service.catalog;

import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.client.shopee.ShopeeCatalogClient;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import com.smartomni.integration.client.tiktok.TiktokCatalogClient;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceStockPushService {

    private final JdbcTemplate jdbc;
    private final ShopeeCatalogClient shopeeCatalogClient;
    private final TiktokCatalogClient tiktokCatalogClient;
    private final MarketplaceConnectionRepository connectionRepository;

    @org.springframework.transaction.annotation.Transactional
    public void pushStock(Long tenantId, String platformStr, Long skuId, int quantity) {
        log.info("[StockPush] Starting stock push: tenantId={}, platform={}, skuId={}, quantity={}",
                tenantId, platformStr, skuId, quantity);

        // RLS context comes from the caller's TenantContext scope: RlsJpaDialect applies it with
        // transaction-local set_config when this @Transactional method starts. Never set it here at
        // session scope, where it would stay on the pooled connection for the next request.
        Long scopedTenantId = TenantContext.getTenantId();
        if (tenantId == null || !tenantId.equals(scopedTenantId)) {
            throw new IllegalStateException("Stock push requires a tenant scope for tenantId=" + tenantId);
        }

        String sql = """
            SELECT l.id, l.connection_id, l.platform, l.platform_item_id, l.external_sku_id, l.seller_sku
            FROM product_marketplace_links l
            WHERE l.tenant_id = ? AND l.sku_id = ? AND LOWER(l.platform::text) = LOWER(?)
            """;

        List<Map<String, Object>> links = jdbc.queryForList(sql, tenantId, skuId, platformStr);
        if (links.isEmpty()) {
            // Deliberately a no-op: the SKU has no link on this platform, so there is nothing to send and
            // retrying would never succeed. The event is acknowledged; the warning keeps it visible.
            log.warn("[StockPush] No active marketplace link found for tenantId={}, skuId={}, platform={}",
                    tenantId, skuId, platformStr);
            return;
        }

        for (Map<String, Object> link : links) {
            Long linkId = ((Number) link.get("id")).longValue();
            Long connectionId = link.get("connection_id") != null ? ((Number) link.get("connection_id")).longValue() : null;
            String pItemId = (String) link.get("platform_item_id");
            String extSkuId = (String) link.get("external_sku_id");
            String platform = ((String) link.get("platform")).toLowerCase();

            try {
                if ("shopee".equals(platform)) {
                    if (connectionId == null) {
                        connectionId = connectionRepository.findByTenantIdAndPlatform(tenantId, MarketplaceConnection.Platform.SHOPEE)
                                .map(MarketplaceConnection::getId)
                                .orElse(null);
                    }
                    if (connectionId == null) {
                        log.warn("[StockPush] No Shopee connection found for tenantId={}", tenantId);
                        continue;
                    }

                    Long itemId = Long.parseLong(pItemId);
                    Long modelId = 0L;
                    if (extSkuId != null && !extSkuId.isBlank()) {
                        try {
                            modelId = Long.parseLong(extSkuId);
                            if (modelId.equals(itemId)) {
                                modelId = 0L;
                            }
                        } catch (NumberFormatException ignored) {
                            modelId = 0L;
                        }
                    }

                    log.info("[StockPush] Invoking Shopee updateStock: connId={}, itemId={}, modelId={}, stock={}",
                            connectionId, itemId, modelId, quantity);
                    shopeeCatalogClient.updateStock(connectionId, itemId, modelId, quantity);

                    jdbc.update("UPDATE product_marketplace_links SET last_synced_at = NOW() WHERE id = ?",
                            linkId);
                    log.info("[StockPush] Successfully updated Shopee stock on Render: itemId={}, stock={}", itemId, quantity);

                } else if ("tiktok_shop".equals(platform) || "tiktok".equals(platform)) {
                    // Skipping silently here would let the outbox mark the event SENT although nothing was sent.
                    if (connectionId == null) {
                        throw new BusinessException("TikTok link " + linkId + " has no connection",
                                HttpStatus.CONFLICT, "TIKTOK_LINK_WITHOUT_CONNECTION");
                    }
                    // Returns only when TikTok accepted the SKU (2xx, code 0, SKU not in data.errors).
                    tiktokCatalogClient.updateStock(connectionId, pItemId, extSkuId, quantity);
                }
            } catch (BusinessException | TiktokApiException ex) {
                // Keep the explicit error code and HTTP status (TiktokApiExceptionHandler / GlobalExceptionHandler).
                log.error("[StockPush] Failed pushing stock for linkId={}: {}", linkId, ex.getMessage());
                throw ex;
            } catch (Exception ex) {
                log.error("[StockPush] Failed pushing stock for linkId={}: {}", linkId, ex.getMessage(), ex);
                throw new RuntimeException("Marketplace stock update failed: " + ex.getMessage(), ex);
            }
        }
    }
}
