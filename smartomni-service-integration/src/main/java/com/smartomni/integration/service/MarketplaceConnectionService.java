package com.smartomni.integration.service;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.integration.dto.ConnectMarketplaceRequest;
import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.repository.MarketplaceConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-18: Cau hinh ket noi san TMDT - FR-038, FR-039
 * UC-40: Tuy chon tu dong nhap san pham khi Onboarding - FR-080, FR-081
 */
@Service
@RequiredArgsConstructor
public class MarketplaceConnectionService {

    private final MarketplaceConnectionRepository connectionRepository;
    private final AesEncryptionService encryptionService;
    private final ProductSyncService productSyncService;

    @Transactional
    public MarketplaceConnection connect(ConnectMarketplaceRequest request) {
        Long tenantId = TenantContext.getTenantId();
        MarketplaceConnection.Platform platform = MarketplaceConnection.Platform.valueOf(request.getPlatform());
        if (platform == MarketplaceConnection.Platform.TIKTOK_SHOP) {
            // The app key/secret belong to the SmartOmni app, never to a shop owner: TikTok uses the authorization flow.
            throw new BusinessException("Use the TikTok Shop authorization flow (/api/marketplace-connections/tiktok-shop)",
                    HttpStatus.BAD_REQUEST, "TIKTOK_USE_AUTHORIZATION_FLOW");
        }
        if (platform == MarketplaceConnection.Platform.SHOPEE) {
            // The partner ID/key belong to the SmartOmni app: Shopee uses the authorization flow.
            throw new BusinessException("Use the Shopee authorization flow (/api/marketplace-connections/shopee)",
                    HttpStatus.BAD_REQUEST, "SHOPEE_USE_AUTHORIZATION_FLOW");
        }

        MarketplaceConnection connection = connectionRepository
                .findByTenantIdAndPlatform(tenantId, platform)
                .orElseGet(MarketplaceConnection::new);

        connection.setTenantId(tenantId);
        connection.setPlatform(platform);
        connection.setAppKey(request.getAppKey());
        connection.setAppSecretEncrypted(encryptionService.encrypt(request.getAppSecret())); // FR-038
        connection.setWebhookUrl(String.format("/webhook/%d/%s", tenantId, platform.name().toLowerCase())); // FR-039
        connection.setStatus(MarketplaceConnection.ConnectionStatus.CONNECTED);
        connection.setAutoImportProducts(request.isAutoImportProducts()); // FR-080, FR-081
        connection = connectionRepository.save(connection);

        // UC-41: neu Admin chon tu dong nhap -> kich hoat ngay quy trinh fetch & nhap san pham
        if (connection.isAutoImportProducts()) {
            productSyncService.importAllProducts(tenantId, platform);
        }

        return connection;
    }
}
