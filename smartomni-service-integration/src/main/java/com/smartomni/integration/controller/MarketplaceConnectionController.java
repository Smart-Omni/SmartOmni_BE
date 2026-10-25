package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.ConnectMarketplaceRequest;
import com.smartomni.integration.dto.MarketplaceConnectionDto;
import com.smartomni.integration.service.MarketplaceConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** UC-18: Cau hinh ket noi san TMDT. UC-40: tuy chon tu dong nhap san pham. */
@RestController
@RequestMapping("/api/marketplace-connections")
@RequiredArgsConstructor
public class MarketplaceConnectionController {

    private final MarketplaceConnectionService connectionService;

    @PostMapping
    public ApiResponse<MarketplaceConnectionDto> connect(@Valid @RequestBody ConnectMarketplaceRequest request) {
        return ApiResponse.success("Ket noi san TMDT thanh cong", MarketplaceConnectionDto.from(connectionService.connect(request)));
    }
}
