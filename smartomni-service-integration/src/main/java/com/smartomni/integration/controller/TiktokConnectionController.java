package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.tiktok.TiktokAuthorizationStartDto;
import com.smartomni.integration.dto.tiktok.TiktokAuthorizationStartRequest;
import com.smartomni.integration.dto.tiktok.TiktokCallbackRequest;
import com.smartomni.integration.dto.tiktok.TiktokConnectionDto;
import com.smartomni.integration.service.tiktok.TiktokConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin endpoints for the TikTok Shop connection. All of them need the Tenant Admin's JWT; the
 * tenant is always taken from it. Responses are DTOs that never contain tokens, secrets or the shop cipher.
 * (Paths sit under /api/marketplace-connections/ because that is the prefix nginx routes to this service.)
 */
@RestController
@RequestMapping("/api/v1/marketplace-connections/tiktok-shop")
@RequiredArgsConstructor
public class TiktokConnectionController {

    private final TiktokConnectionService service;

    @PostMapping("/authorization")
    public ApiResponse<TiktokAuthorizationStartDto> start(
            @RequestParam(value = "redirect_uri", required = false) String queryRedirectUri,
            @RequestBody(required = false) TiktokAuthorizationStartRequest request) {
        String redirectUri = request != null && request.redirectUri() != null && !request.redirectUri().isBlank()
                ? request.redirectUri() : queryRedirectUri;
        return ApiResponse.success(service.start(redirectUri));
    }

    /** Called by the frontend callback page with code/state taken from the redirect query string. */
    @PostMapping("/callback")
    public ApiResponse<TiktokConnectionDto> callback(@RequestBody TiktokCallbackRequest request) {
        return ApiResponse.success("TikTok Shop connection updated", service.complete(request));
    }

    @GetMapping
    public ApiResponse<TiktokConnectionDto> current() {
        return ApiResponse.success(service.current().orElse(null));
    }

    @PostMapping("/disconnect")
    public ApiResponse<TiktokConnectionDto> disconnect(
            @RequestParam(defaultValue = "false") boolean wipeCatalog) {
        return ApiResponse.success("TikTok Shop disconnected", service.disconnect(wipeCatalog));
    }
}
