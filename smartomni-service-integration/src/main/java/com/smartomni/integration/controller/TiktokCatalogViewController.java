package com.smartomni.integration.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.integration.dto.tiktok.TiktokCatalogItemDto;
import com.smartomni.integration.service.catalog.TiktokCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the TikTok Shop catalog view enriched with SmartOmni internal SKU mappings (D8).
 */
@RestController
@RequestMapping("/api/v1/tiktok/catalog")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'STAFF')")
public class TiktokCatalogViewController {

    private final TiktokCatalogService catalogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TiktokCatalogItemDto>>> listCatalog() {
        return ResponseEntity.ok(ApiResponse.success(catalogService.listCatalogForCurrentTenant()));
    }
}
