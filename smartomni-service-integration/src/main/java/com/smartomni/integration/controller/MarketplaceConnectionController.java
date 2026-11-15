package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.ConnectMarketplaceRequest;
import com.smartomni.integration.dto.MarketplaceConnectionDto;
import com.smartomni.integration.service.MarketplaceConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * UC-18: Cau hinh ket noi san TMDT. UC-40: tuy chon tu dong nhap san pham.
 *
 * @deprecated Legacy endpoint. Modern platforms (Shopee, TikTok Shop) require OAuth 2.0 seller consent
 *             via their dedicated controllers: {@code /api/v1/marketplace-connections/shopee} and
 *             {@code /api/v1/marketplace-connections/tiktok-shop}.
 */
@Deprecated(since = "1.0.0", forRemoval = false)
@RestController
@RequestMapping("/api/v1/marketplace-connections")
@RequiredArgsConstructor
public class MarketplaceConnectionController {

    private final MarketplaceConnectionService connectionService;

    /**
     * @deprecated Use platform-specific OAuth authorization endpoints instead of direct key submission.
     */
    @Deprecated(since = "1.0.0", forRemoval = false)
    @PostMapping
    public ApiResponse<MarketplaceConnectionDto> connect(@Valid @RequestBody ConnectMarketplaceRequest request) {
        return ApiResponse.success("Ket noi san TMDT thanh cong", MarketplaceConnectionDto.from(connectionService.connect(request)));
    }
}
