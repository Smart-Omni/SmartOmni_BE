package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.shopee.ShopeeAuthorizationStartDto;
import com.smartomni.integration.dto.shopee.ShopeeAuthorizationStartRequest;
import com.smartomni.integration.dto.shopee.ShopeeConnectionDto;
import com.smartomni.integration.dto.shopee.UpdateShopeeSettingsRequest;
import com.smartomni.integration.service.shopee.ShopeeConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ShopeeConnectionController Unit Tests")
class ShopeeConnectionControllerTest {

    private ShopeeConnectionService service;
    private ShopeeConnectionController controller;

    @BeforeEach
    void setUp() {
        service = mock(ShopeeConnectionService.class);
        controller = new ShopeeConnectionController(service);
    }

    @Test
    void startWithNoParamDelegatesToServiceWithNull() {
        ShopeeAuthorizationStartDto dto = new ShopeeAuthorizationStartDto("https://shopee.com/oauth?state=xyz", Instant.now());
        when(service.start(null)).thenReturn(dto);

        ApiResponse<ShopeeAuthorizationStartDto> response = controller.start(null, null);

        assertNotNull(response.getData());
        assertEquals("https://shopee.com/oauth?state=xyz", response.getData().authorizationUrl());
        verify(service).start(null);
    }

    @Test
    void startWithQueryParamPassesRedirectUri() {
        ShopeeAuthorizationStartDto dto = new ShopeeAuthorizationStartDto("https://shopee.com/oauth?redirect_uri=smartomni://oauth", Instant.now());
        when(service.start("smartomni://oauth/shopee")).thenReturn(dto);

        ApiResponse<ShopeeAuthorizationStartDto> response = controller.start("smartomni://oauth/shopee", null);

        assertNotNull(response.getData());
        verify(service).start("smartomni://oauth/shopee");
    }

    @Test
    void startWithRequestBodyPassesRedirectUri() {
        ShopeeAuthorizationStartDto dto = new ShopeeAuthorizationStartDto("https://shopee.com/oauth?redirect_uri=smartomni://oauth", Instant.now());
        when(service.start("smartomni://oauth/shopee")).thenReturn(dto);

        ApiResponse<ShopeeAuthorizationStartDto> response = controller.start(null, new ShopeeAuthorizationStartRequest("smartomni://oauth/shopee"));

        assertNotNull(response.getData());
        verify(service).start("smartomni://oauth/shopee");
    }

    @Test
    void updateSettingsCallsService() {
        ShopeeConnectionDto dto = new ShopeeConnectionDto(
                88L, "CONNECTED", false, "727720655", "SmartOmni Shop", "VN", true,
                Instant.now(), Instant.now().plusSeconds(3600), Instant.now().plusSeconds(86400), null, null);
        UpdateShopeeSettingsRequest request = new UpdateShopeeSettingsRequest(true);
        when(service.updateSettings(request)).thenReturn(dto);

        ApiResponse<ShopeeConnectionDto> response = controller.updateSettings(request);

        assertNotNull(response.getData());
        assertTrue(response.getData().autoImportProducts());
        assertEquals("Shopee settings updated", response.getMessage());
        verify(service).updateSettings(request);
    }

    @Test
    void currentReturnsCurrentConnection() {
        ShopeeConnectionDto dto = new ShopeeConnectionDto(
                88L, "CONNECTED", false, "727720655", "SmartOmni Shop", "VN", false,
                Instant.now(), Instant.now().plusSeconds(3600), Instant.now().plusSeconds(86400), null, null);
        when(service.current()).thenReturn(Optional.of(dto));

        ApiResponse<ShopeeConnectionDto> response = controller.current();

        assertNotNull(response.getData());
        assertEquals("727720655", response.getData().shopId());
    }
}
