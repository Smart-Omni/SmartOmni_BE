package com.smartomni.integration.service;

import com.smartomni.integration.client.MarketplaceClient;
import com.smartomni.integration.client.ShopeeClient;
import com.smartomni.integration.dto.MarketplaceProductDto;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import com.smartomni.integration.service.catalog.ImportReport;
import com.smartomni.integration.service.catalog.TiktokProductImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * UC-41: He thong tu dong fetch & nhap san pham tu san TMDT.
 * FR-082 (goi GetProductList), FR-083 (anh xa & tao san pham), FR-084 (phan trang),
 * FR-085 (upsert theo SKU), FR-086 (dung khi vuot gioi han SKU), FR-087 (log loi khong gian doan batch).
 *
 * <p>TikTok Shop is delegated to {@link TiktokProductImportService} (page-token pagination, per-product error
 * report, SKU limit result). Shopee still goes through the legacy stub client below.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSyncService {

    private static final int PAGE_SIZE = 50;

    private final MarketplaceConnectionRepository connectionRepository;
    private final AesEncryptionService encryptionService;
    private final ShopeeClient shopeeClient;
    private final TiktokProductImportService tiktokImport;

    public void importAllProducts(Long tenantId, MarketplaceConnection.Platform platform) {
        if (platform == MarketplaceConnection.Platform.TIKTOK_SHOP) {
            ImportReport report = tiktokImport.importForCurrentTenant();
            log.info("TikTok product import run={} status={} counters={}", report.runId(), report.status(), report.counters());
            return;
        }
        MarketplaceConnection connection = connectionRepository.findByTenantIdAndPlatform(tenantId, platform)
                .orElseThrow(() -> new IllegalStateException("Chua ket noi voi " + platform));

        MarketplaceClient client = shopeeClient;
        String appSecret = encryptionService.decrypt(connection.getAppSecretEncrypted());

        int page = 0;
        int successCount = 0;
        int failCount = 0;

        while (true) {
            // FR-084: phan trang de tranh timeout voi danh sach lon
            List<MarketplaceProductDto> products = client.getProductList(connection.getAppKey(), appSecret, page, PAGE_SIZE);
            if (products.isEmpty()) break;

            for (MarketplaceProductDto product : products) {
                try {
                    // TODO: Shopee import chua duoc trien khai (chi co stub client).
                    successCount++;
                } catch (Exception ex) {
                    log.error("Loi nhap san pham '{}' cho tenant={}: {}", product.getName(), tenantId, ex.getMessage());
                    failCount++;
                }
            }
            page++;
        }

        log.info("Hoan tat dong bo san pham tu {} cho tenant={}: {} thanh cong, {} that bai",
                platform, tenantId, successCount, failCount);
    }
}
